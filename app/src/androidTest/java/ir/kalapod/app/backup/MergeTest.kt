package ir.kalapod.app.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyKind
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.PartyRepository
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The reinstall story: history lives in a backup; after reinstalling, the shopkeeper re-enters the
 * product with its shelf stock and keeps selling; later the backup is added. Nothing may be lost,
 * doubled, or renumbered on the old invoices.
 */
@RunWith(AndroidJUnit4::class)
class MergeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()
    private val bkName = "merge-backup-test.db"
    private lateinit var current: KalapodDatabase

    private fun draft(name: String, barcode: String? = null, opening: Long = 0) = ProductDraft(
        name = name, categoryId = null, unit = ProductUnit.PIECE, salePrice = 150_000, purchasePrice = 100_000,
        minStockMilli = 0, leadTimeDays = 1, barcodes = listOfNotNull(barcode), openingStockMilli = opening,
    )

    @After fun tearDown() {
        if (::current.isInitialized) current.close()
        ctx.deleteDatabase(bkName)
    }

    @Test fun reinstallThenMergeKeepsEverythingOnce(): Unit = runBlocking {
        // --- Old phone: history, saved as a backup file
        ctx.deleteDatabase(bkName)
        val old = Room.databaseBuilder(ctx, KalapodDatabase::class.java, bkName).addMigrations(*KalapodDatabase.MIGRATIONS).build()
        val oldLedger = InventoryLedger(old)
        val rob = (ProductRepository(old, oldLedger).save(draft("رب گوجه فرنگی چین چین", barcode = "6260001")) as SaveResult.Saved).id
        val oldTx = TransactionRepository(old, oldLedger)
        oldTx.postPurchase(PurchaseDraft("old-p", listOf(LineInput(rob, 10_000, 100_000)), paidAmount = 1_000_000, occurredAt = now - 10 * day))
        oldTx.postSale(SaleDraft("old-s", listOf(LineInput(rob, 2_000, 150_000)), customer = PartyRef.New("آقای رضایی"),
            receivedAmount = 100_000, occurredAt = now - 9 * day))
        old.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        old.close()
        val staged = File(ctx.cacheDir, "merge-staged.db").also { ctx.getDatabasePath(bkName).copyTo(it, overwrite = true) }

        // --- Reinstalled phone: product re-entered (same name, no barcode) with its 8 pieces on the shelf
        current = Room.inMemoryDatabaseBuilder(ctx, KalapodDatabase::class.java).build()
        val ledger = InventoryLedger(current)
        val products = ProductRepository(current, ledger)
        val rob2 = (products.save(draft("رب گوجه فرنگی چین چین", opening = 8_000)) as SaveResult.Saved).id
        val tx = TransactionRepository(current, ledger)
        tx.postPurchase(PurchaseDraft("new-p", listOf(LineInput(rob2, 5_000, 120_000)), paidAmount = 600_000, occurredAt = now + 3_600_000))
        val newSale = (tx.postSale(SaleDraft("new-s", listOf(LineInput(rob2, 3_000, 150_000)), receivedAmount = 450_000,
            occurredAt = now + 7_200_000)) as PostResult.Posted)
        assertTrue(newSale.invoiceNumber.endsWith("000001"))

        val merger = BackupMerger(current, ledger)
        val preview = merger.preview(staged)
        assertEquals(2, preview.documentsToAdd)
        assertEquals(1, preview.productsMatched)
        assertEquals(0, preview.productsNew)
        assertEquals(2, preview.invoicesRenumbered)      // P-…-000001 and S-…-000001 exist on both sides
        assertEquals(1, preview.openingCounts)

        merger.merge(staged)

        // One product, not two; stock = old 8 left (counted again as 8) + 5 bought − 3 sold = 10, not 18.
        val all = current.productDao().observeActive().first()
        assertEquals(1, all.size)
        val p = all.single()
        assertEquals(10_000L, p.stockMilli)
        assertEquals(10_000L, current.inventoryDao().stockFromLedger(p.id))

        // Old invoices keep their numbers; the newer ones moved and remember the old number.
        val docs = current.transactionDao().observeDocuments(null, 100).first()
        assertEquals(4, docs.size)
        val byUuid = docs.associateBy { it.tx.clientUuid }
        assertTrue(byUuid.getValue("old-s").invoiceNumber!!.endsWith("000001"))
        assertTrue(byUuid.getValue("new-s").invoiceNumber!!.endsWith("000002"))
        assertTrue(byUuid.getValue("new-s").tx.note!!.contains("شماره قبلی"))
        // Dates are the original ones, so the list is in true time order.
        assertEquals(listOf("new-s", "new-p", "old-s", "old-p"), docs.map { it.tx.clientUuid })

        // The new sale's cost is recomputed knowing the old stock: (8×100k + 5×120k) / 13 × 3.
        val cost = current.transactionDao().items(byUuid.getValue("new-s").tx.id).single().costTotal
        assertEquals(Math.round(1_400_000.0 * 3 / 13), cost)

        // The old credit customer came along with the right balance (300k − 100k paid).
        val bal = PartyRepository(current).observeBalances(PartyKind.CUSTOMER).first().single()
        assertEquals("آقای رضایی", bal.name)
        assertEquals(200_000L, bal.balance)

        // Adding the same backup again changes nothing.
        val again = merger.preview(staged)
        assertEquals(0, again.documentsToAdd)
        merger.merge(staged)
        assertEquals(4, current.transactionDao().observeDocuments(null, 100).first().size)
        assertEquals(10_000L, current.productDao().observeActive().first().single().stockMilli)
        staged.delete()
    }
}
