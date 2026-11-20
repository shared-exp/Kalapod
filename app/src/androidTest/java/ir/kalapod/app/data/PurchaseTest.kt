package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.DashboardRepository
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.PostResult
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.PurchaseDraft
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
class PurchaseTest {
    private lateinit var db: KalapodDatabase
    private lateinit var products: ProductRepository
    private lateinit var tx: TransactionRepository
    private var rice = 0L
    private var oil = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java,
        ).build()
        val ledger = InventoryLedger(db)
        products = ProductRepository(db, ledger)
        tx = TransactionRepository(db, ledger)
        rice = (products.save(ProductDraft(name = "برنج", categoryId = null, unit = ProductUnit.KILOGRAM, salePrice = 2_000_000,
            purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList())) as SaveResult.Saved).id
        oil = (products.save(ProductDraft(name = "روغن", categoryId = null, unit = ProductUnit.PIECE, salePrice = 1_000_000,
            purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList())) as SaveResult.Saved).id
    }

    @After fun tearDown() = db.close()

    @Test fun purchaseUpdatesStockCostAndInvoice() = runBlocking {
        val r = tx.postPurchase(
            PurchaseDraft(
                clientUuid = "a", lines = listOf(LineInput(rice, 10_000, 1_800_000), LineInput(oil, 3_000, 900_000)),
                discount = 100_000, extraCost = 50_000, paidAmount = 20_650_000,
            ),
        ) as PostResult.Posted
        assertEquals(20_650_000L, r.total)
        assertTrue(r.invoiceNumber.matches(Regex("P-\\d{4}-000001")))

        val p = products.get(rice)!!
        assertEquals(10_000L, p.stockMilli)
        assertEquals(1_800_000L, p.lastPurchasePrice)
        // Rice and oil values together equal the net invoice total (discount/extra spread into cost).
        assertEquals(20_650_000L, p.stockValue + products.get(oil)!!.stockValue)

        // Second purchase at a different price → moving weighted average.
        tx.postPurchase(PurchaseDraft(clientUuid = "b", lines = listOf(LineInput(rice, 10_000, 2_200_000)), paidAmount = 22_000_000))
        val avg = products.get(rice)!!
        assertEquals(20_000L, avg.stockMilli)
        assertEquals(p.stockValue + 22_000_000L, avg.stockValue)
        assertEquals(2, db.invoiceDao().countWithPrefix("P-"))

        val today = DashboardRepository(db.transactionDao(), db.productDao()).daySummary(LocalDate.now()).first()
        assertEquals(20_650_000L + 22_000_000L, today.netPurchases)
    }

    @Test fun samePurchaseTwiceIsPostedOnce() = runBlocking {
        val d = PurchaseDraft(clientUuid = "dup", lines = listOf(LineInput(oil, 1_000, 900_000)), paidAmount = 900_000)
        assertTrue(tx.postPurchase(d) is PostResult.Posted)
        assertTrue(tx.postPurchase(d) is PostResult.AlreadyPosted)
        assertEquals(1_000L, products.get(oil)!!.stockMilli)
    }

    @Test fun creditPurchaseNeedsSupplierAndRecordsPartialPayment() = runBlocking {
        val noSupplier = PurchaseDraft(clientUuid = "c1", lines = listOf(LineInput(oil, 2_000, 900_000)), paidAmount = 0)
        assertTrue(tx.postPurchase(noSupplier) is PostResult.Invalid)

        val r = tx.postPurchase(
            noSupplier.copy(clientUuid = "c2", supplier = PartyRef.New("پخش البرز"), paidAmount = 500_000, paidMethod = PaymentMethod.CARD),
        ) as PostResult.Posted
        val t = db.transactionDao().get(r.transactionId)!!
        val payments = db.paymentDao().forTransaction(r.transactionId)
        assertEquals(1, payments.size)
        assertEquals(500_000L, payments.single().amount)
        assertEquals("پخش البرز", db.partyDao().observeSuppliers().first().single().name)
        assertEquals(t.supplierId, payments.single().supplierId)
    }
}
