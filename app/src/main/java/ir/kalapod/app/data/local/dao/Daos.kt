package ir.kalapod.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name") fun observeAll(): Flow<List<CategoryEntity>>
    @Insert suspend fun insert(c: CategoryEntity): Long
    @Update suspend fun update(c: CategoryEntity)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE isArchived = 0 ORDER BY name")
    fun observeActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isArchived = 0 AND name LIKE '%' || :q || '%' ORDER BY name")
    fun search(q: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id") suspend fun get(id: Long): ProductEntity?
    @Query("SELECT * FROM products WHERE id = :id") fun observe(id: Long): Flow<ProductEntity?>

    @Query("SELECT p.* FROM products p JOIN product_barcodes b ON b.productId = p.id WHERE b.code = :code")
    suspend fun findByBarcode(code: String): ProductEntity?

    @Query("SELECT * FROM products WHERE isArchived = 0 AND stockMilli <= minStockMilli ORDER BY name")
    fun observeLowStock(): Flow<List<ProductEntity>>

    @Query("SELECT COALESCE(SUM(stockValue), 0) FROM products WHERE isArchived = 0")
    fun observeStockValue(): Flow<Long>

    @Insert suspend fun insert(p: ProductEntity): Long
    @Update suspend fun update(p: ProductEntity)

    @Query("UPDATE products SET stockMilli = :stock, stockValue = :value, updatedAt = :now WHERE id = :id")
    suspend fun setStockCache(id: Long, stock: Long, value: Long, now: Long)
}

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM product_barcodes WHERE productId = :productId")
    suspend fun forProduct(productId: Long): List<ProductBarcodeEntity>
    @Insert suspend fun insert(b: ProductBarcodeEntity): Long
    @Delete suspend fun delete(b: ProductBarcodeEntity)
}

@Dao
interface InventoryDao {
    @Insert suspend fun insert(m: InventoryMovementEntity): Long

    @Query("SELECT COALESCE(SUM(quantityDeltaMilli),0) FROM inventory_movements WHERE productId = :productId")
    suspend fun stockFromLedger(productId: Long): Long

    @Query("SELECT COALESCE(SUM(valueDelta),0) FROM inventory_movements WHERE productId = :productId")
    suspend fun valueFromLedger(productId: Long): Long

    @Query("SELECT * FROM inventory_movements WHERE productId = :productId ORDER BY occurredAt DESC, id DESC")
    fun observeForProduct(productId: Long): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE transactionId = :txId")
    suspend fun forTransaction(txId: Long): List<InventoryMovementEntity>
}

@Dao
interface TransactionDao {
    @Insert suspend fun insert(t: TransactionEntity): Long
    @Insert suspend fun insertItems(items: List<TransactionItemEntity>): List<Long>
    @Query("SELECT * FROM transactions WHERE clientUuid = :uuid") suspend fun byUuid(uuid: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transaction_items WHERE transactionId = :id") suspend fun items(id: Long): List<TransactionItemEntity>
    @Query("UPDATE transactions SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: TransactionStatus)

    @Query("SELECT * FROM transactions WHERE occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    /** Total of posted (non-reversed) documents of a type in a time range. */
    @Query(
        "SELECT COALESCE(SUM(total),0) FROM transactions WHERE type = :type AND status = 'POSTED' " +
            "AND reversesId IS NULL AND occurredAt BETWEEN :from AND :to",
    )
    fun observeTotal(type: TransactionType, from: Long, to: Long): Flow<Long>

    /** Sum of (line revenue - line cost) for posted documents of [type], before invoice-level discount. */
    @Query(
        "SELECT COALESCE(SUM(i.lineTotal - i.costTotal),0) FROM transaction_items i " +
            "JOIN transactions t ON t.id = i.transactionId " +
            "WHERE t.type = :type AND t.status = 'POSTED' AND t.reversesId IS NULL " +
            "AND t.occurredAt BETWEEN :from AND :to",
    )
    fun observeLineMargin(type: TransactionType, from: Long, to: Long): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(discount),0) FROM transactions WHERE type = :type AND status = 'POSTED' " +
            "AND reversesId IS NULL AND occurredAt BETWEEN :from AND :to",
    )
    fun observeDiscounts(type: TransactionType, from: Long, to: Long): Flow<Long>

    @Query(
        "SELECT COUNT(*) FROM transactions WHERE type = :type AND status = 'POSTED' " +
            "AND reversesId IS NULL AND occurredAt BETWEEN :from AND :to",
    )
    fun observeCount(type: TransactionType, from: Long, to: Long): Flow<Int>
}

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(i: InvoiceEntity): Long
    @Query("SELECT * FROM invoices WHERE transactionId = :txId") suspend fun forTransaction(txId: Long): InvoiceEntity?
    @Query("SELECT COUNT(*) FROM invoices WHERE number LIKE :prefix || '%'") suspend fun countWithPrefix(prefix: String): Int
}

@Dao
interface PartyDao {
    @Query("SELECT * FROM customers ORDER BY name") fun observeCustomers(): Flow<List<CustomerEntity>>
    @Query("SELECT * FROM suppliers ORDER BY name") fun observeSuppliers(): Flow<List<SupplierEntity>>
    @Insert suspend fun insertCustomer(c: CustomerEntity): Long
    @Insert suspend fun insertSupplier(s: SupplierEntity): Long
    @Update suspend fun updateCustomer(c: CustomerEntity)
    @Update suspend fun updateSupplier(s: SupplierEntity)
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(p: PaymentEntity): Long
    @Query("SELECT * FROM payments WHERE transactionId = :txId") suspend fun forTransaction(txId: Long): List<PaymentEntity>
}

@Dao
interface StockAlertDao {
    @Query("SELECT * FROM stock_alerts WHERE resolvedAt IS NULL ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<StockAlertEntity>>
    @Query("SELECT * FROM stock_alerts WHERE productId = :productId AND resolvedAt IS NULL LIMIT 1")
    suspend fun openFor(productId: Long): StockAlertEntity?
    @Insert suspend fun insert(a: StockAlertEntity): Long
    @Query("UPDATE stock_alerts SET resolvedAt = :now WHERE productId = :productId AND resolvedAt IS NULL")
    suspend fun resolve(productId: Long, now: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SettingEntity)
}
