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
    ],
    version = 1,
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
    abstract fun balanceDao(): ir.kalapod.app.data.local.dao.BalanceDao

    companion object { const val NAME = "kalapod.db" }
}
