package com.ahcrazy.video2.data.offline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// Same JNI names/signatures as the Android wrappers. No Android, network or model weights needed.
final class WhisperJni {
    native long create();
    native void cancel(long h);
    native void destroy(long h);
    native void load(long h, byte[] path, int threads);
    native boolean supportsFp16();
    native long arm64Hwcap2();
    native int[] speechBounds(long h, short[] samples, byte[] path);
    native byte[][] decode(long h, short[] samples, byte[] lang, byte[] prompt, boolean fast, Object progress);
}

final class HymtJni {
    native long create();
    native void cancel(long h);
    native void destroy(long h);
    native boolean supportsDotprod();
    native void load(long h, byte[] path, int threads, boolean compact);
    native byte[] translate(long h, byte[] prompt, int seed);
}

public final class OfflineNativeSmoke {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("video2_whisper");
        System.loadLibrary("video2_hymt");
        if (args.length > 0) {
            probe(args);
            return;
        }
        var model = Files.createTempFile("video2-invalid-وزن-🎞", ".bin");
        try {
            Files.write(model, new byte[128]);
            byte[] bad = model.toString().getBytes(StandardCharsets.UTF_8);
            var w = new WhisperJni();
            if (w.supportsFp16() || w.arm64Hwcap2() != -1L) throw new AssertionError("Host smoke build must not report Android CPU features");
            long wHandle = w.create();
            try {
                mustFail(() -> w.load(wHandle, bad, 1), "Whisper could not load its model");
                mustFail(() -> w.decode(wHandle, new short[16000], new byte[0], new byte[0], false, null), "Whisper model is not loaded");
                mustFail(() -> w.speechBounds(wHandle, new short[16000], bad), "Could not open Silero VAD");
                w.cancel(wHandle);
                mustFail(() -> w.load(wHandle, bad, 1), "On-device work cancelled");
                mustFail(() -> w.speechBounds(wHandle, new short[16000], bad), "On-device work cancelled");
            } finally { w.destroy(wHandle); }
            var h = new HymtJni();
            if (h.supportsDotprod()) throw new AssertionError("Host smoke build must use the generic translator");
            long hHandle = h.create();
            try {
                mustFail(() -> h.load(hHandle, bad, 1, false), "Hy-MT2 could not load its model");
                mustFail(() -> h.translate(hHandle, new byte[0], 1), "Hy-MT2 model is not loaded");
                h.cancel(hHandle);
                mustFail(() -> h.load(hHandle, bad, 1, false), "On-device work cancelled");
            } finally { h.destroy(hHandle); }
            System.out.println("Both native libraries loaded; invalid-model and cancellation checks passed.");
        } finally { Files.deleteIfExists(model); }
    }

    // Optional probes use user-supplied verified weights and media; nothing is downloaded.
    private static void probe(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("vad")) {
            var input = readPcm(args[2]);
            var engine = new WhisperJni();
            long handle = engine.create();
            try {
                long start = System.nanoTime();
                int[] bounds = engine.speechBounds(handle, input, args[1].getBytes(StandardCharsets.UTF_8));
                elapsed("Voice activity detection (one window)", start);
                for (int i = 0; i < bounds.length; i += 2) {
                    System.out.printf(Locale.ROOT, "%.2f\t%.2f%n", bounds[i] / 100.0, bounds[i + 1] / 100.0);
                }
            } finally { engine.destroy(handle); }
            return;
        }
        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException(
            "vad WEIGHTS PCM16LE or translate 1.8b|7b WEIGHTS PROMPT_UTF8 [THREADS] or speech|speech-fast WEIGHTS PCM16LE SOURCE [THREADS]");
        int threads = args.length == 5 ? Integer.parseInt(args[4]) : 4;
        if (threads < 1 || threads > 32) throw new IllegalArgumentException("Threads must be in 1..32");
        if (args[0].equals("translate")) {
            boolean compact = switch (args[1]) {
                case "1.8b" -> true;
                case "7b" -> false;
                default -> throw new IllegalArgumentException("Expected 1.8b or 7b");
            };
            var prompt = Path.of(args[3]);
            if (Files.size(prompt) > 8192) throw new IllegalArgumentException("Prompt too long");
            byte[] input = Files.readAllBytes(prompt);
            var engine = new HymtJni();
            long handle = engine.create();
            try {
                long start = System.nanoTime();
                engine.load(handle, args[2].getBytes(StandardCharsets.UTF_8), threads, compact);
                elapsed("Model load", start);
                start = System.nanoTime();
                byte[] output = engine.translate(handle, input, 51);
                elapsed("Translation (one prompt)", start);
                if (output == null || output.length == 0) throw new IllegalStateException("No complete translation");
                System.out.println(new String(output, StandardCharsets.UTF_8));
            } finally { engine.destroy(handle); }
        } else if (args[0].equals("speech") || args[0].equals("speech-fast")) {
            var input = readPcm(args[2]);
            var engine = new WhisperJni();
            long handle = engine.create();
            try {
                long start = System.nanoTime();
                engine.load(handle, args[1].getBytes(StandardCharsets.UTF_8), threads);
                elapsed("Model load", start);
                start = System.nanoTime();
                byte[][] output = engine.decode(handle, input, args[3].getBytes(StandardCharsets.UTF_8), new byte[0], args[0].equals("speech-fast"), null);
                elapsed("Transcription (one window)", start);
                for (byte[] line : output) System.out.println(new String(line, StandardCharsets.UTF_8));
            } finally { engine.destroy(handle); }
        } else throw new IllegalArgumentException("Unknown probe: " + args[0]);
    }

    private static short[] readPcm(String path) throws Exception {
        var pcm = Path.of(path);
        long bytes = Files.size(pcm);
        if (bytes <= 0 || bytes > 2L * 16000 * 75 || bytes % 2 != 0) {
            throw new IllegalArgumentException("Expected up to 75 s of raw 16 kHz mono s16le");
        }
        short[] input = new short[(int) (bytes / 2)];
        ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input);
        return input;
    }

    private static void elapsed(String stage, long start) {
        System.err.printf(Locale.ROOT, "%s: %.3f s%n", stage, (System.nanoTime() - start) / 1e9);
    }

    private static void mustFail(Runnable call, String expected) {
        try { call.run(); }
        catch (IllegalStateException e) {
            if (expected.equals(e.getMessage())) return;
            throw e;
        }
        throw new AssertionError("Expected: " + expected);
    }
}
