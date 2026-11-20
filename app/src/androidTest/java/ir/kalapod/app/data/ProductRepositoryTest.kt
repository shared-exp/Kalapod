package ir.kalapod.app.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.local.entity.StockAlertType
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.SaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductRepositoryTest {
    private lateinit var db: KalapodDatabase
    private lateinit var repo: ProductRepository
    private lateinit var ledger: InventoryLedger

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java,
        ).build()
        ledger = InventoryLedger(db)
        repo = ProductRepository(db, ledger)
    }

    @After fun tearDown() = db.close()

    private fun draft(name: String, barcodes: List<String> = emptyList(), opening: Long = 0, min: Long = 0) = ProductDraft(
        name = name, categoryId = null, unit = ProductUnit.KILOGRAM, salePrice = 2_000_000,
        purchasePrice = 1_800_000, minStockMilli = min, leadTimeDays = 2, barcodes = barcodes, openingStockMilli = opening,
    )

    @Test fun openingStockGoesThroughLedger() = runBlocking {
        val id = (repo.save(draft("برنج هاشمی", opening = 10_000)) as SaveResult.Saved).id
        val p = repo.get(id)!!
        assertEquals(10_000L, p.stockMilli)
        assertEquals(18_000_000L, p.stockValue)
        assertEquals(10_000L, db.inventoryDao().stockFromLedger(id))
        assertEquals(18_000_000L, db.inventoryDao().valueFromLedger(id))
    }

    @Test fun duplicateNameAndBarcodeRejected() = runBlocking {
        repo.save(draft("روغن لادن", barcodes = listOf("6260000000001")))
        assertTrue(repo.save(draft("روغن لادن")) is SaveResult.Invalid)
        assertTrue(repo.save(draft("روغن دیگر", barcodes = listOf("6260000000001"))) is SaveResult.Invalid)
        assertEquals("روغن لادن", repo.findByBarcode("6260000000001")?.name)
    }

    @Test fun multipleBarcodesAndSearch() = runBlocking {
        val id = (repo.save(draft("نوشابه خانواده", barcodes = listOf("111", "222"))) as SaveResult.Saved).id
        assertEquals(id, repo.findByBarcode("222")?.id)
        assertEquals(1, repo.observe("نوشابه", null, false).first().size)
        assertEquals(1, repo.observe("111", null, false).first().size)
    }

    @Test fun lowStockAlertLifecycle() = runBlocking {
        val id = (repo.save(draft("پسته اکبری", opening = 2_000, min = 5_000)) as SaveResult.Saved).id
        assertEquals(StockAlertType.LOW_STOCK, db.stockAlertDao().openFor(id)?.type)
        assertEquals(1, repo.observe("", null, lowOnly = true).first().size)

        db.withTransaction { ledger.adjust(id, 10_000, "شمارش انبار", at = 1) }
        assertEquals(null, db.stockAlertDao().openFor(id))
        assertEquals(0, repo.observe("", null, lowOnly = true).first().size)
    }

    @Test fun archivedHiddenButKept() = runBlocking {
        val id = (repo.save(draft("ماکارونی")) as SaveResult.Saved).id
        repo.setArchived(id, true)
        assertEquals(0, repo.observe("", null, false).first().size)
        assertTrue(repo.get(id)!!.isArchived)
    }
}
