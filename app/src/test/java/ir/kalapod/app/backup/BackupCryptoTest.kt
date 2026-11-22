package ir.kalapod.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupCryptoTest {
    private val data = ByteArray(50_000) { (it * 31).toByte() }
    private fun enc(pw: String): ByteArray =
        ByteArrayOutputStream().also { BackupCrypto.encrypt(data, pw.toCharArray(), it, iterations = 10_000) }.toByteArray()

    @Test fun roundTrip() {
        val e = enc("رمز-۱۲۳۴۵۶")
        assertFalse(e.copyOfRange(37, 100).contentEquals(data.copyOfRange(0, 63))) // actually encrypted
        assertArrayEquals(data, BackupCrypto.decrypt(ByteArrayInputStream(e), "رمز-۱۲۳۴۵۶".toCharArray()))
    }

    @Test fun wrongPassword() {
        val e = enc("correct-horse")
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(e), "wrong-horse".toCharArray())
        }
    }

    @Test fun tamperedFileRejected() {
        val e = enc("correct-horse")
        e[e.size - 5] = (e[e.size - 5] + 1).toByte()
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(e), "correct-horse".toCharArray())
        }
        val header = enc("correct-horse").also { it[20] = (it[20] + 1).toByte() } // salt changed
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(header), "correct-horse".toCharArray())
        }
    }

    @Test fun notABackup() {
        assertThrows(BackupCrypto.NotABackupException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream("hello world, not a backup at all....................".toByteArray()), "x".toCharArray())
        }
    }
}
