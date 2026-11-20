package ir.kalapod.app.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the offline Persian speech model once (≈62 MB download, ≈140 MB on disk) and loads it.
 * Kept out of the APK so the app stays small; after installing, voice works with no internet.
 */
@Singleton
class OfflineModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir get() = File(context.filesDir, "vosk-fa")
    private val mutex = Mutex()
    private var loaded: Model? = null

    val isInstalled: Boolean get() = File(dir, "am/final.mdl").exists() && File(dir, "conf/model.conf").exists()

    suspend fun model(): Model = mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { Model(dir.absolutePath) }.also { loaded = it }
    }

    /** Downloads and unpacks the model; reports progress 0..1. Safe to retry after a failure. */
    suspend fun install(onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "vosk-fa.zip")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            try {
                check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: EXPECTED_BYTES
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress((done.toFloat() / total).coerceAtMost(0.99f))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val staging = File(context.filesDir, "vosk-fa.tmp").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Strip the top folder ("vosk-model-small-fa-0.5/…") and skip the bundled test audio.
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.startsWith("test/")) continue
                    val out = File(staging, rel)
                    require(out.canonicalPath.startsWith(staging.canonicalPath)) { "bad zip entry" }
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            check(File(staging, "am/final.mdl").exists()) { "model incomplete" }
            dir.deleteRecursively()
            check(staging.renameTo(dir)) { "could not install model" }
            tmp.delete()
            onProgress(1f)
        }
    }

    fun uninstall() { loaded?.close(); loaded = null; dir.deleteRecursively() }

    companion object {
        // TODO(step 5.1): serve from our own backend/CDN so availability doesn't depend on a third party.
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip"
        const val EXPECTED_BYTES = 62_153_530L
    }
}
