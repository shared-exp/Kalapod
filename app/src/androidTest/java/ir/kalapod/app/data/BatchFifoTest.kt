package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
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
import ir.kalapod.app.domain.finance.Fifo
import ir.kalapod.app.domain.finance.LineInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shop's rule: a new batch with a new price is not averaged in. The old batch is sold out first at
 * its own price and cost; then the new batch's price applies.
 */
@RunWith(AndroidJUnit4::class)
class BatchFifoTest {
    private lateinit var db: KalapodDatabase
    private lateinit var ledger: InventoryLedger
    private lateinit var products: ProductRepository
    private lateinit var tx: TransactionRepository
    private var id = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KalapodDatabase::class.java).build()
        ledger = InventoryLedger(db)
        products = ProductRepository(db, ledger)
        tx = TransactionRepository(db, ledger)
        // Old batch: 20 bought at 800, sold at 1,000. New batch: 5 bought at 900, sold at 1,100.
        id = (products.save(ProductDraft(name = "Test", categoryId = null, unit = ProductUnit.PIECE, salePrice = 10_000,
            purchasePrice = 8_000, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList(), openingStockMilli = 20_000)) as SaveResult.Saved).id
        tx.postPurchase(PurchaseDraft("p1", listOf(LineInput(id, 5_000, 9_000, salePrice = 11_000)), paidAmount = 45_000))
        Unit
    }

    @After fun tearDown() = db.close()

    @Test fun oldBatchKeepsItsPriceUntilSoldOut() = runBlocking {
        assertEquals("shelf price stays the old batch's", 10_000L, products.get(id)!!.salePrice)
        assertEquals(listOf(Fifo.PriceTier(20_000, 10_000), Fifo.PriceTier(2_000, 11_000)), products.priceTiers(id, 22_000))

        // Sell 22 as the cart sends it: 20 at the old price, 2 at the new.
        val sale = tx.postSale(SaleDraft("s1", listOf(LineInput(id, 20_000, 10_000), LineInput(id, 2_000, 11_000)), receivedAmount = 222_000)) as PostResult.Posted
        val items = db.transactionDao().items(sale.transactionId)
        assertEquals(listOf(160_000L, 18_000L), items.map { it.costTotal })   // 20×800, then 2×900 — no averaging
        val p = products.get(id)!!
        assertEquals(3_000L, p.stockMilli)
        assertEquals(27_000L, p.stockValue)
        assertEquals("old batch gone → new price on the shelf", 11_000L, p.salePrice)

        // Cancelling the sale puts every unit back into its own batch: old price is on sale again.
        tx.reverse(sale.transactionId, "اشتباه")
        val b = ledger.batches(id).filter { it.remainingMilli > 0 }
        assertEquals(listOf(20_000L, 5_000L), b.map { it.remainingMilli })
        assertEquals(listOf(160_000L, 45_000L), b.map { it.remainingValue })
        assertEquals(10_000L, products.get(id)!!.salePrice)
    }

    @Test fun saleReturnGoesBackToTheBatchItCameFrom() = runBlocking {
        val sale = tx.postSale(SaleDraft("s1", listOf(LineInput(id, 20_000, 10_000), LineInput(id, 2_000, 11_000)), receivedAmount = 222_000)) as PostResult.Posted
        tx.postReturn(ReturnDraft("r1", sale.transactionId, listOf(ReturnLine(id, 2_000)), refundAmount = 0))
        val b = ledger.batches(id).filter { it.remainingMilli > 0 }
        assertEquals("returned units rejoin the new batch", listOf(5_000L), b.map { it.remainingMilli })
        assertEquals(45_000L, b.single().remainingValue)
    }

    @Test fun purchaseReturnLeavesFromItsOwnBatch() = runBlocking {
        val purchase = db.transactionDao().byUuid("p1")!!.id
        tx.postReturn(ReturnDraft("pr1", purchase, listOf(ReturnLine(id, 5_000)), refundAmount = 0))
        val b = ledger.batches(id).filter { it.remainingMilli > 0 }
        assertEquals(listOf(20_000L), b.map { it.remainingMilli })
        assertEquals(160_000L, b.single().remainingValue)
    }

    @Test fun oversoldUnitsAreTakenFromTheNextPurchase() = runBlocking {
        tx.postSale(SaleDraft("s1", listOf(LineInput(id, 27_000, 10_000)), receivedAmount = 270_000, allowOversell = true))
        assertEquals(-2_000L, products.get(id)!!.stockMilli)
        tx.postPurchase(PurchaseDraft("p2", listOf(LineInput(id, 10_000, 9_500, salePrice = 12_000)), paidAmount = 95_000))
        val p = products.get(id)!!
        assertEquals(8_000L, p.stockMilli)
        val b = ledger.batches(id)
        assertEquals("no batch left negative", 0, b.count { it.remainingMilli < 0 })
        assertEquals(listOf(8_000L), b.filter { it.remainingMilli > 0 }.map { it.remainingMilli })
        assertEquals(12_000L, p.salePrice)
        assertEquals(p.stockValue, b.sumOf { it.remainingValue })
    }

    @Test fun editingThePriceChangesOnlyTheBatchOnSale() = runBlocking {
        val p = products.get(id)!!
        products.save(ProductDraft(id = id, name = p.name, categoryId = null, unit = p.unit, salePrice = 10_500,
            purchasePrice = p.lastPurchasePrice, minStockMilli = 0, leadTimeDays = 1, barcodes = emptyList()))
        assertEquals(listOf(Fifo.PriceTier(20_000, 10_500), Fifo.PriceTier(5_000, 11_000)), products.priceTiers(id, 25_000))
    }
}
