# Kadr 2.6 / 21 · Experimental silence skipping and Spanish/Italian → Arabic

## Two independent experiments

1. **Whisper: Skip long quiet gaps.** Select a source language explicitly, use the same Whisper model and decoding settings as before, and opt in. Auto and Moonshine (which already has its own VAD) keep their existing paths. The new Spanish/Italian shortcuts opt in and retain your selected installed Whisper model; if it isn't installed or is Moonshine, they select multilingual Small Q5_1. They do not download anything until you ask. CPU is the initial path for these shortcuts; Vulkan and the existing advanced choices are still available where supported.
2. **Fast vs dialogue context.** Firefox uses ES→EN→AR or IT→EN→AR *on the device*. It translates independent timed cues in bounded batches and shares the existing EN→AR bundle with both routes. An English pivot can lose gender, idioms, speaker relationships and other clues from the original. For example, Spanish *¿Ya estás lista?* and *¿Ya estás listo?* can both become English *Are you ready?*. Select **سياق أدق** for direct Hy‑MT2, or use **سياق أدق · من نفس التفريغ (Hy‑MT2)** on a completed Firefox job to compare just the translation. This sends the original text and nearby cues to Hy‑MT2 locally, not Firefox's English or Arabic output. It creates a separate job and SRT files; the newest completion is attached automatically, and each result can still be opened from its own job. Retranslating cannot correct a line missed by the ASR.

### Weights (not in the APK)

| Bundle | Download |
| --- | ---: |
| [Silero v6.2.0](https://huggingface.co/ggml-org/whisper-vad/blob/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin) | 885,098 B |
| Firefox ES→EN v2.0 | 37,014,089 B |
| Firefox IT→EN v2.0 | 37,088,127 B |
| Firefox shared EN→AR v2.2 | 35,565,070 B |
| Hy‑MT2 1.8B Q4_K_M | 1,133,080,448 B (reuse if installed) |

French/other language pairs still use the existing Hy‑MT2 options. The new Firefox legs use the exact file sizes, attachment IDs and SHA‑256s from [Foxlet v0.5.0's registry](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/registry.json), corroborated by [the Spanish](https://github.com/slaviboy/VideoSubtitles-translation-models/releases/tag/firefox-bergamot-es-v1) and [Italian](https://github.com/slaviboy/VideoSubtitles-translation-models/releases/tag/firefox-bergamot-it-v1) mirror metadata. The shared Arabic files are unchanged. Backups transfer bundles using the existing optional local model folder. Video, PCM and transcripts remain private to Kadr unless explicitly exported.

### Timing and safety checks

- Same 16 kHz windows, 50 s core and up to 4 s overlap on either side. Silero uses CPU, threshold 0.35, a 100 ms minimum speech detection length, at least 1.4 s detected silence to split, and padding (250 ms at the detector plus 250 ms when planning a cut). Remove only gaps of at least 2.5 s *after* padding that pass a short-block energy check. A window with no detected speech is not skipped unless nearly silent. Packed windows must save at least 5 s and 20% of Whisper's PCM input. Music/noise may veto a cut; the gate can cost rather than save time on dense dialogue.
- PCM compaction is temporary. Kept spans map decoder times back onto the original window, then onto the video clock before the normal overlap deduplication. If a cue crosses a cut, has invalid timing, or a retained span has no cue, discard that attempt and retry the full PCM window. Two such retries disable compaction for the rest of the attempt. A missing, invalid or unavailable detector leaves the original Whisper decode unchanged. Stop is checked before and after the bounded detector call; it isn't a per-frame Silero interrupt.
- A gate-on job has its own ASR checkpoint directory and persists its selection. Existing jobs default to off; a verified full transcript is reused without opening either ASR model or Silero. Forks check the same library video and source-file fingerprint (path, size and modification time), validate the saved source cue times, and use atomic per-job files. Firefox commits no partial batch on a short or invalid response. Batches and results are namespaced by route.
- **Performance** separates PCM extraction, detection, ASR CPU/GPU, translation and SRT export. “Audio omitted from Whisper” measures shortened *decoder inputs* for the current attempt, including overlap; it isn't a cut in video duration or a measure of saved wall time. Retried windows don't count as saved audio. A resumed job cannot be compared with a fresh job's total time.

## Galaxy S26 Ultra A/B checklist

1. Start with two locally saved 2–4 minute clips: one with several long quiet pauses, one with dense dialogue, background noise or music. Use the same explicitly selected language, same installed Whisper model, beam setting, execution path and translator. Create **fresh** jobs, gate off and gate on; don't resume or reuse an existing transcript for this comparison. Keep battery/temperature conditions similar, and repeat in reverse order if timings are close.
2. Compare **time through SRT export** and the ASR/detector breakdown. Listen around each pause and the 50 s seams. Check for missing/duplicated phrases, late starts, overruns, numbers and names. A 1.5× improvement on a pause-heavy clip is a test target, not a promise. Dense dialogue should be judged first on whether it kept the same meaning and timing. If something is missing, repeat without the gate.
3. Separately use one saved Spanish transcript and one saved Italian transcript with both MT paths. Check that both have the same time bounds and cue count. Review who is being addressed, negation, idioms, names and quantities. Compare translation-to-export times independently of ASR. No videos need to be uploaded for this test.
4. Only then try a longer video to test heat, Stop/resume, and battery. An APK from the debug CI run is not evidence of matching your installed signature. **Do not uninstall** your existing private library to get around a signature mismatch; first verify a backup or use a build signed with the original key.

Automated tests exercise window packing and remapping, noisy/dense and cancellation guards, same-source reuse, shared model lookup and batch commits with fake engines. These tests do not measure real recognition or translation accuracy. The native VAD smoke probe can be run with `bash android/tools/smoke-offline.sh /tmp/v2-libs vad ggml-silero-v6.2.0.bin window.pcm` on Linux; it prints pairs of original-window times for at most 75 seconds of 16 kHz mono signed 16-bit little-endian PCM. It does not run Whisper recognition. Galaxy S26 Ultra speed, real-dialogue accuracy and download/install on the handset still need validation.
