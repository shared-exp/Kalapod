package ir.kalapod.app.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup file format (all big-endian):
 *   "KLPD" | version:1 byte | iterations:4 bytes | salt:16 | iv:12 | AES-256-GCM ciphertext+tag
 * The key is derived from the user's backup password with PBKDF2-HMAC-SHA256, so a backup can be
 * restored on any phone with the password, and is useless without it.
 */
object BackupCrypto {
    private val MAGIC = "KLPD".toByteArray()
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 150_000
    const val MIN_PASSWORD = 6

    class WrongPasswordException : Exception("رمز پشتیبان درست نیست یا فایل خراب است.")
    class NotABackupException : Exception("این فایل، فایل پشتیبان کالاپاد نیست.")

    fun encrypt(plain: ByteArray, password: CharArray, out: OutputStream, iterations: Int = DEFAULT_ITERATIONS) {
        require(password.size >= MIN_PASSWORD) { "password too short" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        // The header is authenticated too, so tampering with it is detected.
        val header = header(iterations, salt, iv)
        cipher.updateAAD(header)
        out.write(header)
        out.write(cipher.doFinal(plain))
    }

    fun decrypt(input: InputStream, password: CharArray): ByteArray {
        val all = input.readBytes()
        val headerLen = MAGIC.size + 1 + 4 + 16 + 12
        if (all.size < headerLen + 16 || !all.copyOfRange(0, 4).contentEquals(MAGIC)) throw NotABackupException()
        if (all[4] != VERSION) throw NotABackupException()
        val iterations = ((all[5].toInt() and 0xff) shl 24) or ((all[6].toInt() and 0xff) shl 16) or
            ((all[7].toInt() and 0xff) shl 8) or (all[8].toInt() and 0xff)
        if (iterations !in 10_000..5_000_000) throw NotABackupException()
        val salt = all.copyOfRange(9, 25)
        val iv = all.copyOfRange(25, 37)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(all.copyOfRange(0, headerLen))
        return try {
            cipher.doFinal(all, headerLen, all.size - headerLen)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(MAGIC); write(VERSION.toInt())
        write(iterations ushr 24); write(iterations ushr 16); write(iterations ushr 8); write(iterations)
        write(salt); write(iv)
    }.toByteArray()

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
