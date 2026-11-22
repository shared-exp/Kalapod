package ir.kalapod.app.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ir.kalapod.app.core.jalali.JalaliDate
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Daily encrypted backup into the folder the user picked; keeps the newest [KEEP] files. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun backupManager(): BackupManager
        fun backupPrefs(): BackupPrefs
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val prefs = deps.backupPrefs()
        val s = prefs.settings.first()
        if (!s.autoEnabled) return Result.success()
        val password = prefs.password()
        val folder = s.folderUri?.let { DocumentFile.fromTreeUri(applicationContext, Uri.parse(it)) }
        if (password == null || folder == null || !folder.canWrite()) {
            prefs.recordResult(null, "پوشه یا رمز پشتیبان در دسترس نیست؛ پشتیبان خودکار را دوباره تنظیم کنید.")
            return Result.failure()
        }
        return runCatching {
            val file = folder.createFile("application/octet-stream", fileName()) ?: error("cannot create file")
            applicationContext.contentResolver.openOutputStream(file.uri)!!.use { deps.backupManager().create(it, password) }
            folder.listFiles().filter { it.name?.startsWith(PREFIX) == true }
                .sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
            prefs.recordResult(System.currentTimeMillis(), null)
        }.fold({ Result.success() }, {
            prefs.recordResult(null, "پشتیبان خودکار انجام نشد: ${it.message}")
            Result.retry()
        })
    }

    companion object {
        const val PREFIX = "kalapod-"
        const val EXT = ".kpb"
        private const val KEEP = 7
        private const val NAME = "auto-backup"

        fun fileName(): String {
            val j = JalaliDate.of(LocalDate.now())
            return "%s%04d-%02d-%02d-%d%s".format(PREFIX, j.year, j.month, j.day, System.currentTimeMillis() % 100_000, EXT)
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
