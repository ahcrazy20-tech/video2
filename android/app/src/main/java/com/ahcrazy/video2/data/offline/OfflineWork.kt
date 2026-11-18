package com.ahcrazy.video2.data.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.IBinder
import android.os.OperationCanceledException
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ahcrazy.video2.MainActivity
import com.ahcrazy.video2.Video2Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class OfflineIssue(val en: String, val ar: String) {
    BACKGROUND("Couldn't start the background task. Reopen Video2 and retry.", "تعذر بدء المهمة في الخلفية. افتح Video2 وحاول تاني."),
    NOT_SUPPORTED("On-device models require an ARM64 phone and enough physical RAM.", "الموديلات المحلية تحتاج جهاز ARM64 ورام فعلية كافية."),
    MEMORY("Not enough free RAM. Close other apps, then resume.", "الرام المتاحة مش كفاية. اقفل التطبيقات التانية وبعدها استأنف."),
    LARGE_MODEL_MEMORY("Not enough free RAM for Large-v3. Close other apps, or start a new job with Medium if the speech is clear.", "الرام المتاحة مش كفاية لـLarge-v3. اقفل التطبيقات التانية، أو ابدأ مهمة بـMedium لو الكلام واضح."),
    TRANSLATOR_MEMORY("Transcript saved. Translation paused for memory. Close other apps, then resume from here.",
        "التفريغ محفوظ. الترجمة وقفت مؤقتًا عشان الرام. اقفل التطبيقات التانية واضغط استئناف عشان تكمل من هنا."),
    RETIRED_TRANSLATOR("This job used the old Q6 translator. Start a new on-device job with Q4. Finished subtitles are unchanged.", "المهمة دي كانت بتستخدم موديل Q6 القديم. ابدأ مهمة جديدة على الجهاز بـQ4. الترجمات المكتملة مفيهاش تغيير."),
    STORAGE("Not enough free storage. Free space and retry.", "المساحة الحرة مش كفاية. فضّي مساحة وحاول تاني."),
    MISSING_MODELS("Install the required models from On-device models, then resume.", "نزّل الموديلات المطلوبة من «الموديلات المحلية»، وبعدها استأنف."),
    DOWNLOAD("Model download interrupted. Retry to continue from the saved bytes.", "تنزيل الموديل اتوقف. حاول تاني عشان يكمل من المكان اللي وقف عنده."),
    CHECKSUM("The model failed verification. Download it again.", "ملف الموديل ماعدّاش فحص التحقق. نزّله تاني."),
    VIDEO("The saved file is missing or changed. Start a new job with an existing file.", "الملف المحفوظ اتحذف أو اتغير. ابدأ مهمة جديدة بملف موجود."),
    AUDIO("This file doesn't have a supported local audio track. Try a saved MP4 or audio file.", "الملف مفيهوش مسار صوت محلي مدعوم. جرّب ملف MP4 أو صوت محفوظ."),
    TOO_LONG("This file exceeds the 12-hour local audio limit.", "الملف أطول من حد التفريغ المحلي: ١٢ ساعة."),
    NO_SPEECH("Whisper didn't find clear speech in this audio.", "Whisper ما قدرش يلاقي كلام واضح في الصوت."),
    LANGUAGE("The detected source language isn't supported by this translator. If detection was wrong, pick the source manually in a new job.", "اللغة اللي اتكشفت مش مدعومة في الترجمة. لو الكشف غلط، اختار لغة المصدر يدويًا في مهمة جديدة."),
    TRANSCRIPTION("On-device transcription failed. Check free RAM and resume.", "التفريغ على الجهاز فشل. راجع الرام المتاحة وحاول تستأنف."),
    TRANSLATION("The on-device model couldn't return a complete translation. Resume to retry the remaining lines.", "الموديل المحلي ما رجّعش ترجمة كاملة. استأنف لإعادة محاولة السطور المتبقية."),
    SAVE("Couldn't save the local result. Check storage and resume.", "تعذر حفظ النتيجة على الجهاز. راجع المساحة وحاول تستأنف.");

    fun text(arabic: Boolean) = if (arabic) ar else en
}

internal class OfflineFailure(val issue: OfflineIssue, cause: Throwable? = null) : Exception(issue.en, cause)
internal enum class OfflineWorkKind { DOWNLOAD, SUBTITLES }
internal data class OfflineWorkStatus(
    val kind: OfflineWorkKind, val key: String, val progress: Double = 0.0, val stopping: Boolean = false
)

/** One foreground slot for downloads or inference: never keep both engines or a download resident. */
internal class OfflineWork(private val context: Context) {
    private data class Task(
        val ticket: Int, val signal: CancellationSignal, val body: (CancellationSignal, (Double) -> Unit) -> Unit,
        val onFinish: (Throwable?) -> Unit, var worker: Job? = null
    )

    private var ticket = 0
    private var task: Task? = null
    private val _status = MutableStateFlow<OfflineWorkStatus?>(null)
    val status = _status.asStateFlow()

    @Synchronized fun submit(
        kind: OfflineWorkKind, key: String, onFinish: (Throwable?) -> Unit,
        body: (CancellationSignal, (Double) -> Unit) -> Unit
    ): Boolean {
        if (task != null) return false
        val pending = Task(++ticket, CancellationSignal(), body, onFinish)
        task = pending
        _status.value = OfflineWorkStatus(kind, key)
        try {
            ContextCompat.startForegroundService(context, Intent(context, OfflineWorkService::class.java).putExtra(TICKET, pending.ticket))
        } catch (e: RuntimeException) {
            task = null
            _status.value = null
            throw OfflineFailure(OfflineIssue.BACKGROUND, e)
        }
        return true
    }

    @Synchronized fun attach(id: Int, scope: CoroutineScope) {
        val pending = task?.takeIf { it.ticket == id && it.worker == null } ?: return
        // Completion is reported to the job/model store instead of being an uncaught worker exception.
        val worker = scope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }, start = CoroutineStart.LAZY) {
            pending.signal.throwIfCanceled()
            val power = context.getSystemService(PowerManager::class.java)
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Video2:OfflineWork")
            lock.setReferenceCounted(false)
            lock.acquire(6 * 60 * 60 * 1000L)
            try {
                pending.body(pending.signal) { value ->
                    synchronized(this@OfflineWork) {
                        if (task === pending) _status.value = _status.value?.copy(progress = value.coerceIn(0.0, 1.0))
                    }
                }
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
        pending.worker = worker
        worker.invokeOnCompletion { error ->
            val result = if (error != null && pending.signal.isCanceled) OperationCanceledException() else error
            finish(pending, result)
        }
        worker.start()
    }

    private fun finish(pending: Task, error: Throwable?) {
        try { pending.onFinish(error) }
        finally {
            synchronized(this) {
                if (task === pending) {
                    task = null
                    _status.value = null
                }
            }
        }
    }

    fun cancel(kind: OfflineWorkKind, key: String) = requestStop {
        _status.value?.let { it.kind == kind && it.key == key } == true
    }

    fun cancelTicket(id: Int) = requestStop { it.ticket == id }

    private fun requestStop(matches: (Task) -> Boolean) {
        val signal = synchronized(this) {
            val pending = task?.takeIf(matches) ?: return
            _status.value = _status.value?.copy(stopping = true)
            pending.signal
        }
        signal.cancel()
    }

    fun failedToPromote(id: Int) {
        // Called on main before attach(). No native work has started yet.
        val pending = synchronized(this) { task?.takeIf { it.ticket == id && it.worker == null } } ?: return
        finish(pending, OfflineFailure(OfflineIssue.BACKGROUND))
    }

    companion object {
        const val TICKET = "offlineTicket"
        fun cancelled(error: Throwable) = error is CancellationException || error is OperationCanceledException
    }
}

class OfflineWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val work get() = (application as Video2Application).offlineWork
    private var observer: Job? = null
    private var ticket = 0
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val id = intent?.getIntExtra(OfflineWork.TICKET, 0) ?: 0
        if (intent?.action == CANCEL) {
            work.cancelTicket(id)
            if (work.status.value == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        ticket = id
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video2 offline tasks", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(NOTIFICATION, notification(work.status.value)) }
        catch (_: RuntimeException) {
            work.failedToPromote(id)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        work.attach(id, scope)
        if (observer?.isActive != true) observer = scope.launch {
            work.status.collect { status ->
                if (status == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStart)
                } else manager.notify(NOTIFICATION, notification(status))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(status: OfflineWorkStatus?): Notification {
        val open = PendingIntent.getActivity(this, 43, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, ticket, Intent(this, OfflineWorkService::class.java)
            .setAction(CANCEL).putExtra(OfflineWork.TICKET, ticket), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            status?.stopping == true -> "Stopping…"
            status?.kind == OfflineWorkKind.DOWNLOAD -> "Downloading offline models"
            else -> "Generating subtitles on this device"
        }
        return Notification.Builder(this, CHANNEL).setContentTitle("Video2 · Offline").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, ((status?.progress ?: 0.0) * 100).toInt(), status?.progress?.let { it == 0.0 } != false)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    override fun onDestroy() {
        work.cancelTicket(ticket)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "offline_subtitles"
        const val NOTIFICATION = 43
        const val CANCEL = "com.ahcrazy.video2.CANCEL_OFFLINE"
    }
}
