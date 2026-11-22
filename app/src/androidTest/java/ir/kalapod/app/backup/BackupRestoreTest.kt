package ir.kalapod.app.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open() = Room.databaseBuilder(ctx, KalapodDatabase::class.java, KalapodDatabase.NAME).build()
    private fun draft(name: String, opening: Long) = ProductDraft(
        name = name, categoryId = null, unit = ProductUnit.PIECE, salePrice = 1_000_000, purchasePrice = 800_000,
        minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList(), openingStockMilli = opening,
    )

    @Test fun backupThenRestoreBringsBackExactData(): Unit = runBlocking {
        ctx.deleteDatabase(KalapodDatabase.NAME)
        val settings = SettingsRepository(ctx)
        settings.setShopName("فروشگاه آزمایشی")

        var db = open()
        ProductRepository(db, InventoryLedger(db)).save(draft("روغن", 5_000))
        val backup = ByteArrayOutputStream().also { BackupManager(ctx, db, settings).create(it, "secret-123".toCharArray()) }.toByteArray()

        // Changes after the backup must disappear on restore.
        ProductRepository(db, InventoryLedger(db)).save(draft("ماکارونی", 2_000))
        settings.setShopName("نام جدید")
        assertEquals(2, db.productDao().observeActive().first().size)

        val manager = BackupManager(ctx, db, settings)
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            runBlocking { manager.inspect(ByteArrayInputStream(backup), "wrong-pass".toCharArray()) }
        }
        val (info, staged) = manager.inspect(ByteArrayInputStream(backup), "secret-123".toCharArray())
        assertEquals("فروشگاه آزمایشی", info.shopName)
        manager.restore(staged) // closes db

        db = open()
        val products = db.productDao().observeActive().first()
        assertEquals(listOf("روغن"), products.map { it.name })
        assertEquals(5_000L, products.single().stockMilli)
        assertEquals(5_000L, db.inventoryDao().stockFromLedger(products.single().id))
        assertEquals("فروشگاه آزمایشی", settings.settings.first().shopName)
        db.close()
        ctx.deleteDatabase(KalapodDatabase.NAME)
        settings.setShopName("") // leave the device's real settings as we found them
    }
}
