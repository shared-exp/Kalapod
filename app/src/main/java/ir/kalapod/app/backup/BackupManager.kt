package ir.kalapod.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What a backup contains, shown before restoring. */
data class BackupInfo(val createdAt: Long, val shopName: String, val dbVersion: Int)

/**
 * Creates and restores encrypted backups of the whole shop: the database plus user settings.
 * Works regardless of subscription state (SPEC §12).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KalapodDatabase,
    private val settings: SettingsRepository,
) {
    private val dbFile get() = context.getDatabasePath(KalapodDatabase.NAME)

    suspend fun create(out: OutputStream, password: CharArray) = withContext(Dispatchers.IO) {
        // Fold the WAL into the main file so a single file is a consistent snapshot.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val s = settings.settings.first()
        val meta = JSONObject()
            .put("format", 1)
            .put("dbVersion", db.openHelper.readableDatabase.version)
            .put("createdAt", System.currentTimeMillis())
            .put("shopName", s.shopName)
            .put("currency", s.currency.name)
            .put("theme", s.themeMode.name)
        val zipped = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                zip.putNextEntry(ZipEntry("meta.json")); zip.write(meta.toString().toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry(KalapodDatabase.NAME)); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            bos.toByteArray()
        }
        BackupCrypto.encrypt(zipped, password, out)
    }

    /** Decrypts and validates a backup without touching current data. */
    suspend fun inspect(input: InputStream, password: CharArray): Pair<BackupInfo, File> = withContext(Dispatchers.IO) {
        val plain = BackupCrypto.decrypt(input, password)
        var meta: JSONObject? = null
        val staged = File(context.cacheDir, "restore.db").apply { delete() }
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    "meta.json" -> meta = JSONObject(zip.readBytes().decodeToString())
                    KalapodDatabase.NAME -> staged.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val m = meta ?: throw BackupCrypto.NotABackupException()
        if (!staged.exists()) throw BackupCrypto.NotABackupException()
        val current = db.openHelper.readableDatabase.version
        val version = m.optInt("dbVersion")
        if (version > current) throw IllegalStateException("این پشتیبان با نسخه جدیدتری از برنامه ساخته شده؛ ابتدا برنامه را به‌روز کنید.")
        // Must be a healthy SQLite file with our core tables.
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
            check.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst(); require(c.getString(0) == "ok") { "corrupt" } }
            check.rawQuery("SELECT COUNT(*) FROM products", null).use { it.moveToFirst() }
            check.rawQuery("SELECT COUNT(*) FROM transactions", null).use { it.moveToFirst() }
        }
        BackupInfo(m.optLong("createdAt"), m.optString("shopName"), version) to staged.also { stagedMeta = m }
    }

    private var stagedMeta: JSONObject? = null

    /** After a merge: a freshly installed phone has no shop name yet, so take the one from the backup. */
    suspend fun adoptShopNameIfMissing() {
        val name = stagedMeta?.optString("shopName").orEmpty()
        if (name.isNotBlank() && settings.settings.first().shopName.isBlank()) settings.setShopName(name)
    }

    /**
     * Replaces all current data with the staged backup from [inspect]. The app must restart
     * afterwards (the caller does that), since the open database is closed here.
     */
    suspend fun restore(staged: File) = withContext(Dispatchers.IO) {
        val m = stagedMeta
        db.close()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        staged.copyTo(dbFile, overwrite = true)
        staged.delete()
        if (m != null) {
            settings.setShopName(m.optString("shopName"))
            runCatching { settings.setCurrency(Currency.valueOf(m.optString("currency"))) }
            runCatching { settings.setThemeMode(ThemeMode.valueOf(m.optString("theme"))) }
        }
    }
}
