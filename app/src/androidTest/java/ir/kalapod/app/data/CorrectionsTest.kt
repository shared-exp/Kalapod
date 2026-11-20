package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.repository.DashboardRepository
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PostResult
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.PurchaseDraft
import ir.kalapod.app.data.repository.ReturnDraft
import ir.kalapod.app.data.repository.ReturnLine
import ir.kalapod.app.data.repository.SaleDraft
import ir.kalapod.app.data.repository.SaveResult
import ir.kalapod.app.data.repository.TransactionRepository
import ir.kalapod.app.domain.finance.LineInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CorrectionsTest {
    private lateinit var db: KalapodDatabase
    private lateinit var products: ProductRepository
    private lateinit var tx: TransactionRepository
    private lateinit var dashboard: DashboardRepository
    private var oil = 0L
    private var purchaseId = 0L

    @Before fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java,
        ).build()
        val ledger = InventoryLedger(db)
        products = ProductRepository(db, ledger)
        tx = TransactionRepository(db, ledger)
        dashboard = DashboardRepository(db.transactionDao(), db.productDao())
        oil = (
            products.save(
                ProductDraft(
                    name = "روغن", categoryId = null, unit = ProductUnit.PIECE, salePrice = 1_000_000,
                    purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList(),
                ),
            ) as SaveResult.Saved
            ).id
        // 10 pcs @ 800,000 rial
        purchaseId = (tx.postPurchase(PurchaseDraft("p", listOf(LineInput(oil, 10_000, 800_000)), paidAmount = 8_000_000)) as PostResult.Posted).transactionId
    }

    @After fun tearDown() = db.close()

    private suspend fun sell(uuid: String, qty: Long, discount: Long = 0): Long =
        (tx.postSale(SaleDraft(uuid, listOf(LineInput(oil, qty, 1_000_000)), discount = discount, receivedAmount = qty * 1_000 - discount)) as PostResult.Posted).transactionId

    @Test fun saleReturnRestoresStockAndProfit() = runBlocking {
        val saleId = sell("s", 4_000, discount = 400_000) // 4 pcs, revenue 3,600,000, cost 3,200,000
        val r = tx.postReturn(ReturnDraft("r", saleId, listOf(ReturnLine(oil, 1_000)), refundAmount = 900_000)) as PostResult.Posted
        assertTrue(r.invoiceNumber.startsWith("SR-"))
        assertEquals(900_000L, r.total) // 1,000,000 − 100,000 discount share
        assertEquals(7_000L, products.get(oil)!!.stockMilli)
        assertEquals(5_600_000L, products.get(oil)!!.stockValue)

        val day = dashboard.daySummary(LocalDate.now()).first()
        assertEquals(3_600_000L - 900_000L, day.netSales)
        // profit: (4,000,000 − 3,200,000 − 400,000) − (1,000,000 − 800,000 − 100,000) = 300,000
        assertEquals(300_000L, day.grossProfit)
        assertEquals(PaymentDirection.OUT, db.paymentDao().forTransaction(r.transactionId).single().direction)
    }

    @Test fun cannotReturnMoreThanSold() = runBlocking {
        val saleId = sell("s", 2_000)
        assertTrue(tx.postReturn(ReturnDraft("r1", saleId, listOf(ReturnLine(oil, 1_500)), 0)) is PostResult.Posted)
        assertTrue(tx.postReturn(ReturnDraft("r2", saleId, listOf(ReturnLine(oil, 1_000)), 0)) is PostResult.Invalid)
        assertTrue(tx.postReturn(ReturnDraft("r3", saleId, listOf(ReturnLine(oil, 500)), 0)) is PostResult.Posted)
    }

    @Test fun purchaseReturnRemovesStockAtOriginalCost() = runBlocking {
        val r = tx.postReturn(ReturnDraft("pr", purchaseId, listOf(ReturnLine(oil, 2_000)), refundAmount = 1_600_000)) as PostResult.Posted
        assertEquals(1_600_000L, r.total)
        assertEquals(8_000L, products.get(oil)!!.stockMilli)
        assertEquals(6_400_000L, products.get(oil)!!.stockValue)
        assertEquals(8_000_000L - 1_600_000L, dashboard.daySummary(LocalDate.now()).first().netPurchases)
    }

    @Test fun reversalUndoesEverythingAndKeepsHistory() = runBlocking {
        val saleId = sell("s", 3_000)
        val before = dashboard.daySummary(LocalDate.now()).first()
        assertEquals(3_000_000L, before.netSales)

        assertTrue(tx.reverse(saleId, "") is PostResult.Invalid) // reason required
        assertTrue(tx.reverse(saleId, "اشتباه در ثبت") is PostResult.Posted)
        assertTrue(tx.reverse(saleId, "دوباره") is PostResult.AlreadyPosted)

        val p = products.get(oil)!!
        assertEquals(10_000L, p.stockMilli)
        assertEquals(8_000_000L, p.stockValue)
        assertEquals(TransactionStatus.REVERSED, db.transactionDao().get(saleId)!!.status)
        val after = dashboard.daySummary(LocalDate.now()).first()
        assertEquals(0L, after.netSales)
        assertEquals(0L, after.grossProfit)
        // Ledger keeps both rows: sale −3 and reversal +3.
        assertEquals(10_000L, db.inventoryDao().stockFromLedger(oil))
    }

    @Test fun cannotReverseWhileReturnsExist() = runBlocking {
        val saleId = sell("s", 2_000)
        val retId = (tx.postReturn(ReturnDraft("r", saleId, listOf(ReturnLine(oil, 1_000)), 0)) as PostResult.Posted).transactionId
        assertTrue(tx.reverse(saleId, "x") is PostResult.Invalid)
        assertTrue(tx.reverse(retId, "x") is PostResult.Posted)
        assertTrue(tx.reverse(saleId, "x") is PostResult.Posted)
        assertEquals(10_000L, products.get(oil)!!.stockMilli)
    }

    @Test fun stockAdjustmentNeedsReasonAndGoesToLedger() = runBlocking {
        assertTrue(products.adjustStock(oil, 7_000, "") != null)
        assertNull(products.adjustStock(oil, 7_000, "شمارش"))
        assertEquals(7_000L, products.get(oil)!!.stockMilli)
        assertEquals(5_600_000L, products.get(oil)!!.stockValue)
        assertEquals(7_000L, db.inventoryDao().stockFromLedger(oil))
    }
}
