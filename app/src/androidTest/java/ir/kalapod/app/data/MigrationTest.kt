package ir.kalapod.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.ProductAliasEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading the app must keep every shop's data: v1 database → v2 with the new alias table. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KalapodDatabase::class.java)

    @Test fun v1ToV2KeepsDataAndAddsAliases(): Unit = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO products (id, name, unit, salePrice, lastPurchasePrice, minStockMilli, stockMilli, stockValue, " +
                    "leadTimeDays, isArchived, createdAt, updatedAt) VALUES (1, 'رب گوجه فرنگی چین چین', 'PIECE', 350000, 300000, 0, 5000, 1500000, 1, 0, 0, 0)",
            )
            close()
        }
        // Room validates the migrated schema against the exported v2 schema.
        helper.runMigrationsAndValidate(name, 2, true, *KalapodDatabase.MIGRATIONS).close()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, KalapodDatabase::class.java, name).addMigrations(*KalapodDatabase.MIGRATIONS).build()
        val p = db.productDao().observeActive().first().single()
        assertEquals("رب گوجه فرنگی چین چین", p.name)
        assertEquals(5_000L, p.stockMilli)
        db.aliasDao().put(ProductAliasEntity(alias = "رب شین", productId = 1, createdAt = 0))
        assertEquals(1L, db.aliasDao().all().single().productId)
        db.close()
        ctx.deleteDatabase(name)
    }

    /** v2 → v3: each product's whole history becomes one opening batch holding exactly today's stock, at today's price. */
    @Test fun v2ToV3MakesOneOpeningBatch(): Unit = runBlocking {
        val n = "migration-test-3.db"
        helper.createDatabase(n, 2).apply {
            execSQL(
                "INSERT INTO products (id, name, unit, salePrice, lastPurchasePrice, minStockMilli, stockMilli, stockValue, " +
                    "leadTimeDays, isArchived, createdAt, updatedAt) VALUES (1, 'Test', 'PIECE', 10000, 8000, 0, 15000, 120000, 1, 0, 0, 0)",
            )
            execSQL("INSERT INTO inventory_movements (productId, reason, quantityDeltaMilli, valueDelta, occurredAt) VALUES (1, 'ADJUSTMENT', 20000, 160000, 1)")
            execSQL("INSERT INTO inventory_movements (productId, reason, quantityDeltaMilli, valueDelta, occurredAt) VALUES (1, 'ADJUSTMENT', -5000, -40000, 2)")
            close()
        }
        helper.runMigrationsAndValidate(n, 3, true, *KalapodDatabase.MIGRATIONS).close()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, KalapodDatabase::class.java, n).addMigrations(*KalapodDatabase.MIGRATIONS).build()
        val b = db.inventoryDao().batches(1).single()
        assertEquals(15_000L, b.remainingMilli)
        assertEquals(120_000L, b.remainingValue)
        assertEquals(10_000L, b.salePrice)
        db.close()
        ctx.deleteDatabase(n)
    }
}
