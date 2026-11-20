package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyKind
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.PartyRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BalanceTest {
    private lateinit var db: KalapodDatabase
    private lateinit var tx: TransactionRepository
    private lateinit var parties: PartyRepository
    private var oil = 0L

    @Before fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java,
        ).build()
        val ledger = InventoryLedger(db)
        tx = TransactionRepository(db, ledger)
        parties = PartyRepository(db)
        oil = (
            ProductRepository(db, ledger).save(
                ProductDraft(
                    name = "روغن", categoryId = null, unit = ProductUnit.PIECE, salePrice = 1_000_000,
                    purchasePrice = 0, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList(),
                ),
            ) as SaveResult.Saved
            ).id
    }

    @After fun tearDown() = db.close()

    private suspend fun customerBalance() = parties.observeBalances(PartyKind.CUSTOMER).first().single().balance
    private suspend fun supplierBalance() = parties.observeBalances(PartyKind.SUPPLIER).first().single().balance

    @Test fun customerCreditLifecycle() = runBlocking {
        tx.postPurchase(PurchaseDraft("p", listOf(LineInput(oil, 10_000, 800_000)), paidAmount = 8_000_000))
        // 5 pcs = 5,000,000; pays 1,000,000 now → owes 4,000,000
        val sale = (tx.postSale(SaleDraft("s", listOf(LineInput(oil, 5_000, 1_000_000)), customer = PartyRef.New("رضایی"), receivedAmount = 1_000_000)) as PostResult.Posted).transactionId
        assertEquals(4_000_000L, customerBalance())

        val cid = parties.observeBalances(PartyKind.CUSTOMER).first().single().id
        assertNull(parties.settle(PartyKind.CUSTOMER, cid, 1_500_000, PaymentMethod.CARD, null, "pay1"))
        assertNull(parties.settle(PartyKind.CUSTOMER, cid, 1_500_000, PaymentMethod.CARD, null, "pay1")) // double tap
        assertEquals(2_500_000L, customerBalance())

        // Returns 1 pc, nothing refunded in cash → debt drops by 1,000,000
        tx.postReturn(ReturnDraft("r", sale, listOf(ReturnLine(oil, 1_000)), refundAmount = 0))
        assertEquals(1_500_000L, customerBalance())

        // Mistaken payment reversed → debt back up
        val payId = parties.observeHistory(PartyKind.CUSTOMER, cid).first().first { it.kind == "PAY" }.refId
        assertNotNull(parties.reversePayment(payId, ""))
        assertNull(parties.reversePayment(payId, "اشتباه"))
        assertEquals(3_000_000L, customerBalance())
    }

    @Test fun reversingCreditSaleClearsDebt() = runBlocking {
        tx.postPurchase(PurchaseDraft("p", listOf(LineInput(oil, 10_000, 800_000)), paidAmount = 8_000_000))
        val sale = (tx.postSale(SaleDraft("s", listOf(LineInput(oil, 2_000, 1_000_000)), customer = PartyRef.New("کریمی"), receivedAmount = 500_000)) as PostResult.Posted).transactionId
        assertEquals(1_500_000L, customerBalance())
        tx.reverse(sale, "اشتباه")
        assertEquals(0L, customerBalance())
    }

    @Test fun supplierPayable() = runBlocking {
        tx.postPurchase(PurchaseDraft("p", listOf(LineInput(oil, 10_000, 800_000)), supplier = PartyRef.New("پخش البرز"), paidAmount = 3_000_000))
        assertEquals(5_000_000L, supplierBalance())
        val sid = parties.observeBalances(PartyKind.SUPPLIER).first().single().id
        parties.settle(PartyKind.SUPPLIER, sid, 2_000_000, PaymentMethod.CASH, "قسط اول", "sp1")
        assertEquals(3_000_000L, supplierBalance())
        val hist = parties.observeHistory(PartyKind.SUPPLIER, sid).first()
        assertEquals(2, hist.size)
    }
}
