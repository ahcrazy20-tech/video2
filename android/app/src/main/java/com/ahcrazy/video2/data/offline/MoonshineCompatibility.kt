package com.ahcrazy.video2.data.offline

// Linux AArch64 UAPI, arch/arm64/include/uapi/asm/hwcap.h. Not every NDK defines SME2.
private const val HWCAP2_SME = 1L shl 23
private const val HWCAP2_SME2 = 1L shl 37

/** The ORT 1.23.2 in Moonshine 0.1.6 can dispatch SME2 kernels on SME-only CPUs.
 * A SIGILL terminates the app; catching a JNI exception is not enough. See moonshine#253. */
internal fun moonshineOrtIssueFor(hwcap2: Long): OfflineIssue? = when {
    hwcap2 == -1L -> OfflineIssue.NOT_SUPPORTED // Couldn't read Android ARM64 capabilities.
    (hwcap2 and HWCAP2_SME) != 0L && (hwcap2 and HWCAP2_SME2) == 0L -> OfflineIssue.MOONSHINE_CPU
    else -> null
}

internal val bundledMoonshineIssue: OfflineIssue? by lazy {
    try {
        // This probe lives in the portable Whisper library. Never load Moonshine/ORT to probe them.
        WhisperJni.ensureLoaded()
        moonshineOrtIssueFor(WhisperJni.arm64Hwcap2())
    } catch (_: LinkageError) {
        OfflineIssue.NOT_SUPPORTED
    } catch (_: SecurityException) {
        OfflineIssue.NOT_SUPPORTED
    }
}

internal fun requireBundledMoonshineSupport() {
    bundledMoonshineIssue?.let { throw OfflineFailure(it) }
}

/** Keep a compatible existing Whisper install; prefer Small for the clear-speech shortcut. */
internal fun chooseRapidWhisper(installedAndCompatible: (OfflineModel) -> Boolean): OfflineModel {
    val small = OfflineModel.WHISPER_SMALL_Q5
    if (installedAndCompatible(small)) return small
    return OfflineModel.speechModels.firstOrNull { it != small && !it.isMoonshine && installedAndCompatible(it) } ?: small
}
