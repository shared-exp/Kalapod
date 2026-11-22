package ir.kalapod.app.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupStore by preferencesDataStore(name = "backup")

data class BackupSettings(
    val hasPassword: Boolean = false,
    val autoEnabled: Boolean = false,
    /** SAF tree URI of the folder chosen for automatic backups. */
    val folderUri: String? = null,
    val lastBackupAt: Long? = null,
    val lastError: String? = null,
)

/**
 * Backup preferences. The backup password is remembered (so daily backups can run unattended)
 * encrypted with a hardware-backed Android Keystore key that never leaves the device.
 */
@Singleton
class BackupPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val pwKey = stringPreferencesKey("password_enc")
    private val autoKey = booleanPreferencesKey("auto")
    private val folderKey = stringPreferencesKey("folder")
    private val lastKey = longPreferencesKey("last")
    private val errorKey = stringPreferencesKey("error")

    val settings: Flow<BackupSettings> = context.backupStore.data.map {
        BackupSettings(it[pwKey] != null, it[autoKey] == true, it[folderKey], it[lastKey], it[errorKey])
    }

    suspend fun savePassword(password: CharArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val enc = c.iv + c.doFinal(String(password).toByteArray())
        context.backupStore.edit { it[pwKey] = Base64.encodeToString(enc, Base64.NO_WRAP) }
    }

    suspend fun password(): CharArray? {
        val b64 = context.backupStore.data.first()[pwKey] ?: return null
        return runCatching {
            val all = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12)).toCharArray()
        }.getOrNull()
    }

    suspend fun setAuto(enabled: Boolean, folderUri: String?) = context.backupStore.edit {
        it[autoKey] = enabled
        if (folderUri != null) it[folderKey] = folderUri
    }

    suspend fun recordResult(at: Long?, error: String?) = context.backupStore.edit {
        if (at != null) it[lastKey] = at
        if (error == null) it.remove(errorKey) else it[errorKey] = error
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object { const val ALIAS = "kalapod_backup_password" }
}
