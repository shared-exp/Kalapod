package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyRef
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

/** Editing a posted sale = cancel it and post the corrected one, atomically. */
@RunWith(AndroidJUnit4::class)
class SaleEditTest {
    private lateinit var db: KalapodDatabase
    private lateinit var products: ProductRepository
    private lateinit var tx: TransactionRepository
    private var rice = 0L
    private val day = 86_400_000L
    private val then = System.currentTimeMillis() - 3 * day

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java).build()
        val ledger = InventoryLedger(db)
        products = ProductRepository(db, ledger)
        tx = TransactionRepository(db, ledger)
        rice = (products.save(ProductDraft(name = "برنج", categoryId = null, unit = ProductUnit.KILOGRAM, salePrice = 2_500_000,
            purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList())) as SaveResult.Saved).id
        tx.postPurchase(PurchaseDraft("p1", listOf(LineInput(rice, 20_000, 2_000_000)), paidAmount = 40_000_000, occurredAt = then - day))
        Unit
    }

    @After fun tearDown() = db.close()

    private suspend fun sell(uuid: String, qty: Long, received: Long, customer: PartyRef = PartyRef.None) =
        tx.postSale(SaleDraft(uuid, listOf(LineInput(rice, qty, 2_500_000)), customer = customer, receivedAmount = received, occurredAt = then)) as PostResult.Posted

    @Test fun editReplacesTheSaleAndKeepsItsDate() = runBlocking {
        val orig = sell("s1", 4_000, 4_000_000, PartyRef.New("آقای رضایی"))          // 10M, 4M paid, 6M owed
        val e = tx.saleForEdit(orig.transactionId)!!
        assertEquals(4_000L, e.lines.single().quantityMilli)
        assertEquals(4_000_000L, e.received)
        assertEquals("آقای رضایی", e.customerName)

        val r = tx.correctSale(orig.transactionId, SaleDraft("s1-edit", listOf(LineInput(rice, 3_000, 2_500_000)),
            customer = PartyRef.Existing(e.customerId!!), receivedAmount = 4_000_000, occurredAt = e.occurredAt)) as PostResult.Posted

        assertEquals(TransactionStatus.REVERSED, db.transactionDao().get(orig.transactionId)!!.status)
        val fixed = db.transactionDao().get(r.transactionId)!!
        assertEquals(then, fixed.occurredAt)
        assertTrue(fixed.note!!.contains(orig.invoiceNumber))
        assertEquals(17_000L, products.get(rice)!!.stockMilli)                       // 20 − 3
        val bal = db.balanceDao().observeCustomerBalances().first().single()
        assertEquals(3_500_000L, bal.balance)                                         // 7.5M − 4M
    }

    @Test fun failedEditChangesNothing() = runBlocking {
        val orig = sell("s1", 4_000, 10_000_000)
        val r = tx.correctSale(orig.transactionId, SaleDraft("s1-edit", listOf(LineInput(rice, 50_000, 2_500_000)),
            receivedAmount = 125_000_000, occurredAt = then))
        assertTrue(r is PostResult.InsufficientStock)
        assertEquals(TransactionStatus.POSTED, db.transactionDao().get(orig.transactionId)!!.status)
        assertEquals(16_000L, products.get(rice)!!.stockMilli)
        assertNull(db.transactionDao().byUuid("reverse-${orig.transactionId}"))
    }

    @Test fun saleWithAReturnMustHaveItsReturnCancelledFirst() = runBlocking {
        val orig = sell("s1", 4_000, 10_000_000)
        tx.postReturn(ReturnDraft("r1", orig.transactionId, listOf(ReturnLine(rice, 1_000)), refundAmount = 0))
        val r = tx.correctSale(orig.transactionId, SaleDraft("s1-edit", listOf(LineInput(rice, 2_000, 2_500_000)),
            receivedAmount = 5_000_000, occurredAt = then))
        assertTrue(r is PostResult.Invalid)
        assertEquals(TransactionStatus.POSTED, db.transactionDao().get(orig.transactionId)!!.status)
        assertEquals(17_000L, products.get(rice)!!.stockMilli)
    }

    @Test fun cancelledSaleCannotBeEdited() = runBlocking {
        val orig = sell("s1", 4_000, 10_000_000)
        tx.reverse(orig.transactionId, "اشتباه")
        assertNull(tx.saleForEdit(orig.transactionId))
    }
}
