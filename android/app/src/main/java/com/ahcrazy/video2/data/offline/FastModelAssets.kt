package com.ahcrazy.video2.data.offline

// These IDs also namespace the jobs, checkpoints and private install directories.
// A different weights revision gets a different ID, not an in-place replacement.
internal const val MOONSHINE_ID = "moonshine-small-en-260821"
internal const val FIREFOX_ID = "firefox-en-ar-22"
private const val MOONSHINE_EN = "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21"
private const val FIREFOX_CDN = "https://firefox-settings-attachments.cdn.mozilla.net/main-workspace/translations-models"

private fun pinnedAsset(id: String, name: String, url: String, bytes: Long, sha: String) =
    ModelAsset(name, PinnedModel(url, bytes, sha), "$id-${sha.take(12)}-$name", name)

// Moonshine official dated CDN; sizes/digests cross-checked against the two independent manifests
// linked in docs/fast-local-subtitles.md. Word timings and spelling assets are not used.
internal val moonshineAssets = listOf(
    moonshine("adapter.ort", 2_870_368, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
    moonshine("cross_kv.ort", 5_356_536, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
    moonshine("decoder_kv.ort", 81_878_600, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
    moonshine("encoder.ort", 44_148_576, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
    moonshine("frontend.model.ort", 26_944, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
    moonshine("frontend.weights.ort", 7_769_464, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
    moonshine("streaming_config.json", 512, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
    moonshine("tokenizer.bin", 249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
)

private fun moonshine(name: String, bytes: Long, sha: String) = pinnedAsset(MOONSHINE_ID, name, "$MOONSHINE_EN/$name", bytes, sha)

// Mozilla Remote Settings EN→AR v2.2, also independently mirrored with the same file hashes.
internal val firefoxAssets = listOf(
    firefox("model.enar.intgemm.alphas.bin", "e282aa49-a77e-466e-b1b7-f98f34a9ea58.bin", 31_561_787, "ae659d7045fc2e5d6ba50583586a5d94ee4358d92c019847e37b57a0e627faa8"),
    firefox("lex.50.50.enar.s2t.bin", "d821aec4-485b-4747-b643-6e63cda5117d.bin", 3_139_692, "2b7194817c5dd9225ca90c0d908cc92e1d6d1f45625781a5feb037ac568d6a61"),
    firefox("vocab.enar.spm", "793d9667-7929-4375-855d-96bbed2f113d.spm", 863_591, "8d93c54aa5e2044c416ec680b5ff9af0227bd698521666e8b1a1ea1b041fbae8")
)

private fun firefox(name: String, attachment: String, bytes: Long, sha: String) = pinnedAsset(FIREFOX_ID, name, "$FIREFOX_CDN/$attachment", bytes, sha)

internal fun supportsLocalPair(source: String, target: String, speech: OfflineModel, translator: OfflineModel): Boolean =
    (!speech.isMoonshine || source == "en") && (!translator.isFirefox || (source == "en" && target == "ar"))
