package com.ahcrazy.video2.data.dubbing

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubLine
import com.ahcrazy.video2.core.dubbing.DubProvider
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.DubVoices
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private fun Voice.toChoice() = DubVoice(name, locale.toLanguageTag(), isNetworkConnectionRequired,
    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features.orEmpty(), quality, latency)

/** An engine is scoped to one query or render. Never leave it or a callback running after cancellation. */
internal class AndroidDubEngine private constructor(private val tts: TextToSpeech) {
    fun catalog(): DubVoices {
        val engine = tts.defaultEngine
        val label = tts.engines.firstOrNull { it.name == engine }?.label
        return DubVoices(tts.voices.orEmpty().map { it.toChoice() }
            .filter { it.language.isNotBlank() && it.id.isNotBlank() }.distinctBy { it.tag to it.id },
            tts.defaultVoice?.name, engine, label)
    }

    fun select(voice: DubVoice, preferredEngine: String?) {
        if (voice.provider != DubProvider.ANDROID || tts.defaultEngine != preferredEngine) throw DubFailure(DubIssue.VOICE_CHANGED)
        val actual = tts.voices.orEmpty().firstOrNull { it.name == voice.id && it.locale.toLanguageTag() == voice.tag }
            ?: throw DubFailure(DubIssue.VOICE_CHANGED)
        val access = actual.toChoice()
        if (access.needsDownload) throw DubFailure(DubIssue.NEEDS_DOWNLOAD)
        // If the engine changed the catalog after auditioning, don't silently use a network voice.
        if (access.network != voice.network || tts.setVoice(actual) != TextToSpeech.SUCCESS ||
            tts.voice?.toChoice()?.let { it.id == voice.id && it.tag == voice.tag && it.network == voice.network } != true) {
            throw DubFailure(DubIssue.VOICE_CHANGED)
        }
    }

    suspend fun synthesize(line: DubLine, output: File): SpeechPcmHint {
        currentCoroutineContext().ensureActive()
        if (line.text.length > TextToSpeech.getMaxSpeechInputLength()) throw DubFailure(DubIssue.CUE_TOO_LONG, line.number)
        val id = UUID.randomUUID().toString()
        val complete = CompletableDeferred<Int>()
        val hint = AtomicReference(SpeechPcmHint.HEADER)
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (utteranceId == id && audioFormat == AudioFormat.ENCODING_PCM_FLOAT) hint.set(SpeechPcmHint.FLOAT32)
            }
            override fun onDone(utteranceId: String?) { if (utteranceId == id) complete.complete(TextToSpeech.SUCCESS) }
            @Deprecated("Legacy engine callback")
            override fun onError(utteranceId: String?) { if (utteranceId == id) complete.complete(TextToSpeech.ERROR) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) complete.complete(errorCode) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) complete.complete(TextToSpeech.STOPPED) }
        }
        if (tts.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS) throw DubFailure(DubIssue.SYNTHESIS, line.number)
        try {
            output.delete()
            if (tts.synthesizeToFile(line.text, Bundle(), output, id) != TextToSpeech.SUCCESS) {
                throw DubFailure(DubIssue.SYNTHESIS, line.number)
            }
            when (withTimeoutOrNull(60_000) { complete.await() }) {
                TextToSpeech.SUCCESS -> if (!output.isFile) throw DubFailure(DubIssue.SYNTHESIS, line.number)
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> throw DubFailure(DubIssue.NEEDS_DOWNLOAD, line.number)
                TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> throw DubFailure(DubIssue.NEEDS_NETWORK, line.number)
                else -> throw DubFailure(DubIssue.SYNTHESIS, line.number)
            }
        } finally {
            if (!complete.isCompleted) runCatching { tts.stop() }
            tts.setOnUtteranceProgressListener(null)
        }
        return hint.get()
    }

    companion object {
        suspend fun <T> use(context: Context, action: suspend (AndroidDubEngine) -> T): T = withContext(Dispatchers.IO) {
            val initialized = CompletableDeferred<Int>()
            val tts = TextToSpeech(context.applicationContext) { status -> initialized.complete(status) }
            try {
                if (withTimeoutOrNull(12_000) { initialized.await() } != TextToSpeech.SUCCESS) throw DubFailure(DubIssue.NO_ENGINE)
                action(AndroidDubEngine(tts))
            } finally {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
        }
    }
}
