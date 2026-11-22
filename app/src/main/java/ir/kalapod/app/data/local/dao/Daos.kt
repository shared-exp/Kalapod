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
import ir.kalapod.app.data.local.entity.ProductAliasEntity
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

    @Query(
        "SELECT * FROM products WHERE isArchived = 0 AND " +
            "((minStockMilli > 0 AND stockMilli <= minStockMilli) OR stockMilli < 0) ORDER BY stockMilli, name",
    )
    fun observeLowStock(): Flow<List<ProductEntity>>

    /** List screen query. [categoryId] = null means all; [lowOnly] restricts to low/negative stock. */
    @Query(
        "SELECT * FROM products WHERE isArchived = 0 " +
            "AND (:q = '' OR name LIKE '%' || :q || '%' OR id IN (SELECT productId FROM product_barcodes WHERE code = :q)) " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) " +
            "AND (:lowOnly = 0 OR (minStockMilli > 0 AND stockMilli <= minStockMilli) OR stockMilli < 0) " +
            "ORDER BY name",
    )
    fun observeFiltered(q: String, categoryId: Long?, lowOnly: Boolean): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products WHERE isArchived = 0 AND name = :name AND id != :exceptId")
    suspend fun countByName(name: String, exceptId: Long): Int

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
    @Query("SELECT * FROM product_barcodes WHERE code = :code") suspend fun byCode(code: String): ProductBarcodeEntity?
    @Query("DELETE FROM product_barcodes WHERE productId = :productId") suspend fun deleteForProduct(productId: Long)
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

    /** Quantity of [productId] already returned against [originalId] (posted returns only). */
    @Query(
        "SELECT COALESCE(SUM(i.quantityMilli),0) FROM transaction_items i JOIN transactions t ON t.id = i.transactionId " +
            "WHERE t.originalId = :originalId AND t.status = 'POSTED' AND t.reversesId IS NULL AND i.productId = :productId",
    )
    suspend fun returnedQuantity(originalId: Long, productId: Long): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE originalId = :originalId AND status = 'POSTED' AND reversesId IS NULL")
    suspend fun activeReturnCount(originalId: Long): Int

    /** Document list: business documents only (reversal records are shown inside their original). */
    @Query(
        "SELECT t.*, inv.number AS invoiceNumber, c.name AS customerName, s.name AS supplierName, " +
            "(SELECT COUNT(*) FROM transaction_items i WHERE i.transactionId = t.id) AS itemCount " +
            "FROM transactions t LEFT JOIN invoices inv ON inv.transactionId = t.id " +
            "LEFT JOIN customers c ON c.id = t.customerId LEFT JOIN suppliers s ON s.id = t.supplierId " +
            "WHERE t.reversesId IS NULL AND (:type IS NULL OR t.type = :type) " +
            "ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit",
    )
    fun observeDocuments(type: TransactionType?, limit: Int): Flow<List<DocumentRow>>

    @Query(
        "SELECT i.*, p.name AS productName, p.unit AS unit FROM transaction_items i " +
            "JOIN products p ON p.id = i.productId WHERE i.transactionId = :txId ORDER BY i.id",
    )
    suspend fun itemsWithProduct(txId: Long): List<ItemRow>

    @Query("SELECT * FROM transactions WHERE originalId = :originalId AND reversesId IS NULL ORDER BY occurredAt")
    suspend fun returnsOf(originalId: Long): List<TransactionEntity>
}

data class DocumentRow(
    @androidx.room.Embedded val tx: TransactionEntity,
    val invoiceNumber: String?,
    val customerName: String?,
    val supplierName: String?,
    val itemCount: Int,
)

data class ItemRow(
    @androidx.room.Embedded val item: TransactionItemEntity,
    val productName: String,
    val unit: ir.kalapod.app.data.local.entity.ProductUnit,
)

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

/** A customer or supplier with its running balance (rial). Positive = they owe us / we owe them. */
data class PartyBalance(val id: Long, val name: String, val phone: String?, val balance: Long)

/** One line in a party's account history. */
data class PartyEntry(
    val kind: String,          // "DOC" or "PAY"
    val refId: Long,           // transaction id or payment id
    val label: String,         // transaction type or payment direction
    val method: String?,
    val amount: Long,
    val occurredAt: Long,
    val reversed: Boolean,
    val invoiceNumber: String?,
)

@Dao
interface BalanceDao {
    /*
     * Customer balance = sales − sale returns − money received + money refunded.
     * Only live documents count (POSTED and not a reversal record); payment mirrors from reversals
     * are included so they cancel the originals.
     */
    @Query(
        "SELECT c.id, c.name, c.phone, " +
            "COALESCE((SELECT SUM(CASE t.type WHEN 'SALE' THEN t.total WHEN 'SALE_RETURN' THEN -t.total ELSE 0 END) " +
            "  FROM transactions t WHERE t.customerId = c.id AND t.status = 'POSTED' AND t.reversesId IS NULL), 0) - " +
            "COALESCE((SELECT SUM(CASE p.direction WHEN 'IN' THEN p.amount ELSE -p.amount END) " +
            "  FROM payments p WHERE p.customerId = c.id), 0) AS balance " +
            "FROM customers c ORDER BY balance DESC, c.name",
    )
    fun observeCustomerBalances(): Flow<List<PartyBalance>>

    /** Supplier balance = purchases − purchase returns − money paid + money refunded to us. */
    @Query(
        "SELECT s.id, s.name, s.phone, " +
            "COALESCE((SELECT SUM(CASE t.type WHEN 'PURCHASE' THEN t.total WHEN 'PURCHASE_RETURN' THEN -t.total ELSE 0 END) " +
            "  FROM transactions t WHERE t.supplierId = s.id AND t.status = 'POSTED' AND t.reversesId IS NULL), 0) - " +
            "COALESCE((SELECT SUM(CASE p.direction WHEN 'OUT' THEN p.amount ELSE -p.amount END) " +
            "  FROM payments p WHERE p.supplierId = s.id), 0) AS balance " +
            "FROM suppliers s ORDER BY balance DESC, s.name",
    )
    fun observeSupplierBalances(): Flow<List<PartyBalance>>

    @Query(
        "SELECT 'DOC' AS kind, t.id AS refId, t.type AS label, NULL AS method, t.total AS amount, t.occurredAt, " +
            "(t.status = 'REVERSED') AS reversed, inv.number AS invoiceNumber " +
            "FROM transactions t LEFT JOIN invoices inv ON inv.transactionId = t.id " +
            "WHERE t.customerId = :id AND t.reversesId IS NULL " +
            "UNION ALL SELECT 'PAY', p.id, p.direction, p.method, p.amount, p.occurredAt, " +
            "(p.reversesPaymentId IS NOT NULL OR EXISTS(SELECT 1 FROM payments r WHERE r.reversesPaymentId = p.id)), NULL " +
            "FROM payments p WHERE p.customerId = :id AND p.transactionId IS NULL " +
            "ORDER BY occurredAt DESC",
    )
    fun observeCustomerHistory(id: Long): Flow<List<PartyEntry>>

    @Query(
        "SELECT 'DOC' AS kind, t.id AS refId, t.type AS label, NULL AS method, t.total AS amount, t.occurredAt, " +
            "(t.status = 'REVERSED') AS reversed, inv.number AS invoiceNumber " +
            "FROM transactions t LEFT JOIN invoices inv ON inv.transactionId = t.id " +
            "WHERE t.supplierId = :id AND t.reversesId IS NULL " +
            "UNION ALL SELECT 'PAY', p.id, p.direction, p.method, p.amount, p.occurredAt, " +
            "(p.reversesPaymentId IS NOT NULL OR EXISTS(SELECT 1 FROM payments r WHERE r.reversesPaymentId = p.id)), NULL " +
            "FROM payments p WHERE p.supplierId = :id AND p.transactionId IS NULL " +
            "ORDER BY occurredAt DESC",
    )
    fun observeSupplierHistory(id: Long): Flow<List<PartyEntry>>

    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: Long): PaymentEntity?
    @Query("SELECT COUNT(*) FROM payments WHERE reversesPaymentId = :id") suspend fun reversalCount(id: Long): Int
    @Query("SELECT * FROM payments WHERE clientUuid = :uuid") suspend fun paymentByUuid(uuid: String): PaymentEntity?
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun customer(id: Long): CustomerEntity?
    @Query("SELECT * FROM suppliers WHERE id = :id") suspend fun supplier(id: Long): SupplierEntity?
}

/** One sold/returned line with what's needed for revenue, cost and discount share. */
data class SaleLineRow(
    val type: TransactionType,
    val occurredAt: Long,
    val productId: Long,
    val productName: String,
    val quantityMilli: Long,
    val lineTotal: Long,
    val costTotal: Long,
    val txSubtotal: Long,
    val txDiscount: Long,
)

data class DocTotalRow(val type: TransactionType, val total: Long, val occurredAt: Long)

@Dao
interface ReportDao {
    @Query(
        "SELECT t.type, t.occurredAt, i.productId, p.name AS productName, i.quantityMilli, i.lineTotal, i.costTotal, " +
            "t.subtotal AS txSubtotal, t.discount AS txDiscount " +
            "FROM transaction_items i JOIN transactions t ON t.id = i.transactionId JOIN products p ON p.id = i.productId " +
            "WHERE t.type IN ('SALE','SALE_RETURN') AND t.status = 'POSTED' AND t.reversesId IS NULL " +
            "AND t.occurredAt BETWEEN :from AND :to AND (:productId IS NULL OR i.productId = :productId)",
    )
    suspend fun saleLines(from: Long, to: Long, productId: Long?): List<SaleLineRow>

    @Query(
        "SELECT type, total, occurredAt FROM transactions WHERE status = 'POSTED' AND reversesId IS NULL " +
            "AND occurredAt BETWEEN :from AND :to",
    )
    suspend fun docTotals(from: Long, to: Long): List<DocTotalRow>

    @Query("SELECT id, name FROM products WHERE isArchived = 0")
    suspend fun activeProducts(): List<ProductName>
}

data class ProductName(val id: Long, val name: String)

@Dao
interface AliasDao {
    @Query("SELECT * FROM product_aliases") suspend fun all(): List<ProductAliasEntity>
    /** Re-teaching the same words points them at the newly chosen product. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(a: ProductAliasEntity): Long
}
