package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.DashboardRepository
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.PostResult
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.PurchaseDraft
import ir.kalapod.app.data.repository.SaleDraft
import ir.kalapod.app.data.repository.SaveResult
import ir.kalapod.app.data.repository.TransactionRepository
import ir.kalapod.app.domain.finance.LineInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SaleTest {
    private lateinit var db: KalapodDatabase
    private lateinit var products: ProductRepository
    private lateinit var tx: TransactionRepository
    private lateinit var dashboard: DashboardRepository
    private var rice = 0L

    @Before fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java,
        ).build()
        val ledger = InventoryLedger(db)
        products = ProductRepository(db, ledger)
        tx = TransactionRepository(db, ledger)
        dashboard = DashboardRepository(db.transactionDao(), db.productDao())
        rice = (
            products.save(
                ProductDraft(
                    name = "برنج", categoryId = null, unit = ProductUnit.KILOGRAM, salePrice = 2_500_000,
                    purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList(),
                ),
            ) as SaveResult.Saved
            ).id
        // Two batches: 10 kg @ 1,800,000 then 10 kg @ 2,200,000 (sold oldest first, never averaged)
        tx.postPurchase(PurchaseDraft("p1", listOf(LineInput(rice, 10_000, 1_800_000)), paidAmount = 18_000_000))
        tx.postPurchase(PurchaseDraft("p2", listOf(LineInput(rice, 10_000, 2_200_000)), paidAmount = 22_000_000))
    }

    @After fun tearDown() = db.close()

    @Test fun saleTakesOldestBatchCostAndComputesProfit() = runBlocking {
        val r = tx.postSale(
            SaleDraft("s1", listOf(LineInput(rice, 4_000, 2_500_000)), discount = 200_000, receivedAmount = 9_800_000),
        ) as PostResult.Posted
        assertTrue(r.invoiceNumber.startsWith("S-"))
        val item = db.transactionDao().items(r.transactionId).single()
        assertEquals(7_200_000L, item.costTotal)       // 4 kg × 1,800,000 from the first batch
        assertEquals(10_000_000L, item.lineTotal)

        val p = products.get(rice)!!
        assertEquals(16_000L, p.stockMilli)
        assertEquals(32_800_000L, p.stockValue)     // 6 × 1.8M + 10 × 2.2M

        val today = dashboard.daySummary(LocalDate.now()).first()
        assertEquals(9_800_000L, today.netSales)
        assertEquals(1, today.saleCount)
        assertEquals(10_000_000L - 7_200_000L - 200_000L, today.grossProfit)
    }

    @Test fun oversellRequiresExplicitConfirmation() = runBlocking {
        val d = SaleDraft("s2", listOf(LineInput(rice, 25_000, 2_500_000)), receivedAmount = 62_500_000)
        val refused = tx.postSale(d) as PostResult.InsufficientStock
        assertEquals(20_000L, refused.shortages.single().availableMilli)
        assertEquals(20_000L, products.get(rice)!!.stockMilli) // nothing written

        assertTrue(tx.postSale(d.copy(allowOversell = true)) is PostResult.Posted)
        assertEquals(-5_000L, products.get(rice)!!.stockMilli)
    }

    @Test fun creditSaleTracksCustomerAndPartialPayment() = runBlocking {
        val base = SaleDraft("s3", listOf(LineInput(rice, 2_000, 2_500_000)), receivedAmount = 1_000_000)
        assertTrue(tx.postSale(base) is PostResult.Invalid) // no customer for credit

        val r = tx.postSale(base.copy(clientUuid = "s4", customer = PartyRef.New("آقای رضایی"))) as PostResult.Posted
        val pay = db.paymentDao().forTransaction(r.transactionId).single()
        assertEquals(PaymentDirection.IN, pay.direction)
        assertEquals(1_000_000L, pay.amount)
        assertEquals("آقای رضایی", db.partyDao().observeCustomers().first().single().name)
    }

    @Test fun duplicateSaleIgnored() = runBlocking {
        val d = SaleDraft("dup", listOf(LineInput(rice, 1_000, 2_500_000)), receivedAmount = 2_500_000)
        tx.postSale(d)
        assertTrue(tx.postSale(d) is PostResult.AlreadyPosted)
        assertEquals(19_000L, products.get(rice)!!.stockMilli)
    }
}
