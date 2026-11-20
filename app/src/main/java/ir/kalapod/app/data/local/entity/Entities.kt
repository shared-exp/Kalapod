package ir.kalapod.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Conventions:
 *  - All money columns are Long in RIAL (smallest unit). Toman is a display choice.
 *  - All quantity columns are Long in milli-units of the product's unit.
 *  - Timestamps are epoch millis (UTC); Jalali conversion happens in the UI layer.
 *  - Financial rows are never deleted; corrections are new documents (reversal/return/adjustment).
 */

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "products",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId"), Index("name")],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val categoryId: Long? = null,
    val unit: ProductUnit,
    val imageUri: String? = null,
    val salePrice: Long,
    val lastPurchasePrice: Long = 0,
    val minStockMilli: Long = 0,
    /** Cache of SUM(ledger quantity); rebuildable from inventory_movements. */
    val stockMilli: Long = 0,
    /** Cache of SUM(ledger value) — cost basis under moving weighted average. */
    val stockValue: Long = 0,
    val leadTimeDays: Int = 1,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "product_barcodes",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["code"], unique = true), Index("productId")],
)
data class ProductBarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val code: String,
)

@Entity(tableName = "customers", indices = [Index("name"), Index("phone")])
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "suppliers", indices = [Index("name"), Index("phone")])
data class SupplierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(SupplierEntity::class, ["id"], ["supplierId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TransactionEntity::class, ["id"], ["reversesId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TransactionEntity::class, ["id"], ["originalId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("type", "occurredAt"), Index("customerId"), Index("supplierId"),
        Index("reversesId"), Index("originalId"), Index("occurredAt"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Idempotency key generated when the cart is opened; prevents double posting. */
    val clientUuid: String,
    val type: TransactionType,
    val status: TransactionStatus = TransactionStatus.POSTED,
    val occurredAt: Long,
    val customerId: Long? = null,
    val supplierId: Long? = null,
    val subtotal: Long,
    val discount: Long = 0,
    val extraCost: Long = 0,
    val total: Long,
    /** For returns: the original purchase/sale being returned. */
    val originalId: Long? = null,
    /** For reversal documents: the transaction this one cancels. */
    val reversesId: Long? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("transactionId"), Index("productId")],
)
data class TransactionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val productId: Long,
    val quantityMilli: Long,
    /** Price per one whole unit. */
    val unitPrice: Long,
    val lineTotal: Long,
    /** Cost of goods for this line (sales/returns), from moving average at posting time. */
    val costTotal: Long = 0,
)

/** Inventory ledger. Stock = SUM(quantityDeltaMilli); value = SUM(valueDelta). */
@Entity(
    tableName = "inventory_movements",
    foreignKeys = [
        ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("productId", "occurredAt"), Index("transactionId")],
)
data class InventoryMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val transactionId: Long? = null,
    val reason: MovementReason,
    val quantityDeltaMilli: Long,
    val valueDelta: Long,
    /** Required for ADJUSTMENT. */
    val note: String? = null,
    val occurredAt: Long,
)

@Entity(
    tableName = "invoices",
    foreignKeys = [ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["transactionId"], unique = true), Index(value = ["number"], unique = true)],
)
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    /** Human-facing unique number, e.g. "S-1405-00012". */
    val number: String,
    val issuedAt: Long,
)

@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(SupplierEntity::class, ["id"], ["supplierId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["clientUuid"], unique = true),
        Index("transactionId"), Index("customerId"), Index("supplierId"), Index("occurredAt"),
    ],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val transactionId: Long? = null,
    val customerId: Long? = null,
    val supplierId: Long? = null,
    val direction: PaymentDirection,
    val method: PaymentMethod,
    val amount: Long,
    /** If set, this payment reverses another payment (no hard deletes). */
    val reversesPaymentId: Long? = null,
    val note: String? = null,
    val occurredAt: Long,
)

@Entity(
    tableName = "stock_alerts",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("productId"), Index("resolvedAt")],
)
data class StockAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: StockAlertType,
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    val value: String,
)
