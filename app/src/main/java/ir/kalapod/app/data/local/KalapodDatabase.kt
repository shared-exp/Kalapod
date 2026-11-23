package ir.kalapod.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ir.kalapod.app.data.local.dao.BarcodeDao
import ir.kalapod.app.data.local.dao.CategoryDao
import ir.kalapod.app.data.local.dao.InventoryDao
import ir.kalapod.app.data.local.dao.InvoiceDao
import ir.kalapod.app.data.local.dao.PartyDao
import ir.kalapod.app.data.local.dao.PaymentDao
import ir.kalapod.app.data.local.dao.ProductDao
import ir.kalapod.app.data.local.dao.SettingDao
import ir.kalapod.app.data.local.dao.StockAlertDao
import ir.kalapod.app.data.local.dao.TransactionDao
import ir.kalapod.app.data.local.entity.CategoryEntity
import ir.kalapod.app.data.local.entity.CustomerEntity
import ir.kalapod.app.data.local.entity.InventoryMovementEntity
import ir.kalapod.app.data.local.entity.InvoiceEntity
import ir.kalapod.app.data.local.entity.PaymentEntity
import ir.kalapod.app.data.local.entity.ProductBarcodeEntity
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.SettingEntity
import ir.kalapod.app.data.local.entity.StockAlertEntity
import ir.kalapod.app.data.local.entity.SupplierEntity
import ir.kalapod.app.data.local.entity.TransactionEntity
import ir.kalapod.app.data.local.entity.TransactionItemEntity

@Database(
    entities = [
        CategoryEntity::class, ProductEntity::class, ProductBarcodeEntity::class,
        CustomerEntity::class, SupplierEntity::class,
        TransactionEntity::class, TransactionItemEntity::class, InventoryMovementEntity::class,
        InvoiceEntity::class, PaymentEntity::class, StockAlertEntity::class, SettingEntity::class,
        ir.kalapod.app.data.local.entity.ProductAliasEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KalapodDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao
    abstract fun barcodeDao(): BarcodeDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun partyDao(): PartyDao
    abstract fun paymentDao(): PaymentDao
    abstract fun stockAlertDao(): StockAlertDao
    abstract fun settingDao(): SettingDao
    abstract fun aliasDao(): ir.kalapod.app.data.local.dao.AliasDao
    abstract fun reportDao(): ir.kalapod.app.data.local.dao.ReportDao
    abstract fun balanceDao(): ir.kalapod.app.data.local.dao.BalanceDao

    companion object {
        const val NAME = "kalapod.db"

        /** v2: learned voice aliases. Every future schema change gets its own migration — never destructive. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `product_aliases` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`alias` TEXT NOT NULL, `productId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_product_aliases_alias` ON `product_aliases` (`alias`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_aliases_productId` ON `product_aliases` (`productId`)")
            }
        }
        /**
         * v3: stock batches (FIFO, each with its own cost and sale price). All existing history of a product
         * becomes one opening batch — its remaining stock and value are exactly today's — priced at today's sale price.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `inventory_movements` ADD COLUMN `batchId` INTEGER")
                db.execSQL("ALTER TABLE `inventory_movements` ADD COLUMN `salePrice` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_batchId` ON `inventory_movements` (`batchId`)")
                db.execSQL(
                    "UPDATE inventory_movements SET batchId = (SELECT MIN(m.id) FROM inventory_movements m " +
                        "WHERE m.productId = inventory_movements.productId)",
                )
                db.execSQL(
                    "UPDATE inventory_movements SET salePrice = (SELECT p.salePrice FROM products p " +
                        "WHERE p.id = inventory_movements.productId) WHERE id = batchId",
                )
            }
        }
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
