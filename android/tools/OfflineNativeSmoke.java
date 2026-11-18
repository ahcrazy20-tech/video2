package com.ahcrazy.video2.data.offline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// Same JNI names/signatures as the Android wrappers. No Android, network or model weights needed.
final class WhisperJni {
    native long create();
    native void cancel(long h);
    native void destroy(long h);
    native void load(long h, byte[] path, int threads);
    native byte[][] decode(long h, short[] samples, byte[] lang, byte[] prompt);
}

final class HymtJni {
    native long create();
    native void cancel(long h);
    native void destroy(long h);
    native void load(long h, byte[] path, int threads);
    native byte[] translate(long h, byte[] prompt, int seed);
}

public final class OfflineNativeSmoke {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("video2_whisper");
        System.loadLibrary("video2_hymt");
        var model = Files.createTempFile("video2-invalid-وزن-🎞", ".bin");
        try {
            Files.write(model, new byte[128]);
            byte[] bad = model.toString().getBytes(StandardCharsets.UTF_8);
            var w = new WhisperJni();
            long wHandle = w.create();
            try {
                mustFail(() -> w.load(wHandle, bad, 1), "Whisper could not load its model");
                mustFail(() -> w.decode(wHandle, new short[16000], new byte[0], new byte[0]), "Whisper model is not loaded");
                w.cancel(wHandle);
                mustFail(() -> w.load(wHandle, bad, 1), "On-device work cancelled");
            } finally { w.destroy(wHandle); }
            var h = new HymtJni();
            long hHandle = h.create();
            try {
                mustFail(() -> h.load(hHandle, bad, 1), "Hy-MT2 could not load its model");
                mustFail(() -> h.translate(hHandle, new byte[0], 1), "Hy-MT2 model is not loaded");
                h.cancel(hHandle);
                mustFail(() -> h.load(hHandle, bad, 1), "On-device work cancelled");
            } finally { h.destroy(hHandle); }
            System.out.println("Both native libraries loaded; invalid-model and cancellation checks passed.");
        } finally { Files.deleteIfExists(model); }
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
