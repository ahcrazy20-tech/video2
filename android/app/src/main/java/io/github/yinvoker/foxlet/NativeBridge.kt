package io.github.yinvoker.foxlet

import androidx.annotation.Keep

/** The small file-only JNI surface of the pinned Foxlet v0.5.0 source build. */
@Keep
internal object NativeBridge {
    private val library by lazy { System.loadLibrary("foxlet") }
    fun ensureLoaded() { library }
    external fun createService(workers: Int, cacheSize: Int): Long
    external fun destroyService(service: Long)
    external fun loadModel(service: Long, configYaml: String, ssplitPrefix: ByteArray?): Long
    external fun releaseModel(service: Long, model: Long): Boolean
    external fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean): Array<String>
    external fun translatePivot(service: Long, first: Long, second: Long, texts: Array<String>, html: Boolean): Array<String>
}
