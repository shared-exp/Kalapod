package ir.kalapod.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.PurchaseDraft
import ir.kalapod.app.data.repository.SaleDraft
import ir.kalapod.app.data.repository.SaveResult
import ir.kalapod.app.data.repository.TransactionRepository
import ir.kalapod.app.domain.finance.LineInput
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Manual QA helper, NOT part of the app: fills the emulator's real database with two weeks of
 * activity through the real repositories, so reports and forecasts can be eyeballed.
 * Run explicitly: am instrument -e class ir.kalapod.app.data.ManualSeed …
 */
@RunWith(AndroidJUnit4::class)
class ManualSeed {
    @Test fun seedTwoWeeks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, KalapodDatabase::class.java, KalapodDatabase.NAME).build()
        val ledger = InventoryLedger(db)
        val products = ProductRepository(db, ledger)
        val tx = TransactionRepository(db, ledger)
        val rnd = Random(7)
        data class P(val name: String, val unit: ProductUnit, val buy: Long, val sell: Long, val min: Long)
        val catalog = listOf(
            P("برنج هاشمی", ProductUnit.KILOGRAM, 1_800_000, 2_100_000, 10_000),
            P("روغن لادن", ProductUnit.PIECE, 900_000, 1_050_000, 5_000),
            P("ماکارونی زر", ProductUnit.PACK, 250_000, 320_000, 10_000),
            P("پسته اکبری", ProductUnit.KILOGRAM, 12_000_000, 14_500_000, 2_000),
            P("نوشابه خانواده", ProductUnit.PIECE, 220_000, 280_000, 12_000),
            P("رب گوجه", ProductUnit.PIECE, 300_000, 380_000, 6_000),
        )
        val ids = catalog.map { p ->
            when (val r = products.save(ProductDraft(name = p.name, categoryId = null, unit = p.unit, salePrice = p.sell,
                purchasePrice = p.buy, minStockMilli = p.min, leadTimeDays = 2, barcodes = emptyList()))) {
                is SaveResult.Saved -> r.id
                is SaveResult.Invalid -> return@runBlocking // already seeded
            }
        }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().minusDays(13)
        fun at(d: LocalDate, hour: Int) = d.atTime(hour, rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()

        tx.postPurchase(PurchaseDraft("seed-p0", catalog.mapIndexed { i, p -> LineInput(ids[i], 40_000, p.buy) },
            supplier = PartyRef.New("پخش البرز"), paidAmount = 0, occurredAt = at(start, 8)))
        for (day in 0 until 14) {
            val d = start.plusDays(day.toLong())
            repeat(3 + rnd.nextInt(5)) { n ->
                val picks = catalog.indices.shuffled(rnd).take(1 + rnd.nextInt(3))
                val lines = picks.map { i -> LineInput(ids[i], (1 + rnd.nextInt(3)) * 1_000L, catalog[i].sell) }
                val total = lines.sumOf { it.unitPrice * it.quantityMilli / 1000 }
                val credit = rnd.nextInt(6) == 0
                tx.postSale(SaleDraft("seed-s-$day-$n", lines, customer = if (credit) PartyRef.New("مشتری ${day}${n}") else PartyRef.None,
                    receivedAmount = if (credit) total / 2 else total, allowOversell = true, occurredAt = at(d, 9 + n)))
            }
        }
        db.close()
    }

    /** Several types of one general product, for the «which type?» flow. */
    @Test fun seedTomatoPasteBrands() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, KalapodDatabase::class.java, KalapodDatabase.NAME)
            .addMigrations(*KalapodDatabase.MIGRATIONS).build()
        val products = ProductRepository(db, InventoryLedger(db))
        listOf("رب گوجه فرنگی چین چین" to 6_000L, "رب گوجه فرنگی تبرک" to 3_000L, "رب گوجه فرنگی دلپذیر" to 0L).forEach { (n, stock) ->
            products.save(ProductDraft(name = n, categoryId = null, unit = ProductUnit.PIECE, salePrice = 380_000,
                purchasePrice = 300_000, minStockMilli = 0, leadTimeDays = 2, barcodes = emptyList(), openingStockMilli = stock))
        }
        db.close()
    }
}
