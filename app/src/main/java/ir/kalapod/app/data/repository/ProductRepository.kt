package ir.kalapod.app.data.repository

import androidx.room.withTransaction
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.CategoryEntity
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.local.entity.ProductBarcodeEntity
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.domain.finance.Fifo
import ir.kalapod.app.domain.finance.Money
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the product form edits. Money in rial, quantities in milli-units. */
data class ProductDraft(
    val id: Long = 0,
    val name: String,
    val categoryId: Long?,
    val unit: ProductUnit,
    val salePrice: Long,
    val purchasePrice: Long,
    val minStockMilli: Long,
    val leadTimeDays: Int,
    val barcodes: List<String>,
    /** Only for new products: stock already on the shelf, valued at [purchasePrice]. */
    val openingStockMilli: Long = 0,
    /** Pieces per carton/box (milli), if bought by the carton. */
    val caseSizeMilli: Long? = null,
)

sealed class SaveResult {
    data class Saved(val id: Long) : SaveResult()
    data class Invalid(val message: String) : SaveResult()
    /** A new product with the name of one already in stock: really a new batch of [existing]. */
    data class Duplicate(val existing: ProductEntity) : SaveResult()
}

@Singleton
class ProductRepository @Inject constructor(
    private val db: KalapodDatabase,
    private val ledger: InventoryLedger,
) {
    private val products get() = db.productDao()
    private val barcodes get() = db.barcodeDao()

    fun observe(query: String, categoryId: Long?, lowOnly: Boolean): Flow<List<ProductEntity>> =
        products.observeFiltered(query.trim(), categoryId, lowOnly)

    fun observeCategories(): Flow<List<CategoryEntity>> = db.categoryDao().observeAll()

    suspend fun get(id: Long): ProductEntity? = products.get(id)
    suspend fun barcodesOf(id: Long): List<String> = barcodes.forProduct(id).map { it.code }
    suspend fun findByBarcode(code: String): ProductEntity? = products.findByBarcode(code.trim())

    suspend fun addCategory(name: String): Long = db.categoryDao().insert(CategoryEntity(name = name.trim()))

    suspend fun save(d: ProductDraft, now: Long = System.currentTimeMillis()): SaveResult {
        val name = d.name.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return SaveResult.Invalid("نام کالا را وارد کنید.")
        if (d.salePrice < 0 || d.purchasePrice < 0) return SaveResult.Invalid("قیمت نامعتبر است.")
        val codes = d.barcodes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        return db.withTransaction {
            products.findByName(name, d.id)?.let { existing ->
                return@withTransaction if (d.id == 0L) SaveResult.Duplicate(existing)
                else SaveResult.Invalid("کالای دیگری با این نام وجود دارد.")
            }
            for (c in codes) {
                val owner = barcodes.byCode(c)
                if (owner != null && owner.productId != d.id) {
                    val other = products.get(owner.productId)?.name.orEmpty()
                    return@withTransaction SaveResult.Invalid("بارکد $c قبلاً برای «$other» ثبت شده است.")
                }
            }

            val id = if (d.id == 0L) {
                products.insert(
                    ProductEntity(
                        name = name, categoryId = d.categoryId, unit = d.unit, salePrice = d.salePrice,
                        lastPurchasePrice = d.purchasePrice, minStockMilli = d.minStockMilli,
                        leadTimeDays = d.leadTimeDays, createdAt = now, updatedAt = now, caseSizeMilli = d.caseSizeMilli,
                    ),
                )
            } else {
                val old = products.get(d.id) ?: return@withTransaction SaveResult.Invalid("کالا پیدا نشد.")
                // Stock/value are never edited here; they only change through the ledger.
                products.update(
                    old.copy(
                        name = name, categoryId = d.categoryId, unit = d.unit, salePrice = d.salePrice,
                        lastPurchasePrice = d.purchasePrice, minStockMilli = d.minStockMilli,
                        leadTimeDays = d.leadTimeDays, updatedAt = now, caseSizeMilli = d.caseSizeMilli,
                    ),
                )
                // A new sale price applies to the batch now on sale; later batches keep their own prices.
                if (d.salePrice != old.salePrice) {
                    Fifo.front(ledger.batches(d.id))?.let { db.inventoryDao().setBatchPrice(it.id, d.salePrice) }
                }
                d.id
            }

            barcodes.deleteForProduct(id)
            codes.forEach { barcodes.insert(ProductBarcodeEntity(productId = id, code = it)) }

            if (d.id == 0L && d.openingStockMilli > 0) {
                ledger.receive(
                    id, d.openingStockMilli, Money.lineTotal(d.purchasePrice, d.openingStockMilli),
                    MovementReason.ADJUSTMENT, transactionId = null, at = now, note = "موجودی اولیه",
                    salePrice = d.salePrice,
                )
            } else {
                ledger.refreshAlert(products.get(id)!!, now)
            }
            SaveResult.Saved(id)
        }
    }

    /**
     * Stock count correction: sets stock to [countedMilli] by writing an ADJUSTMENT ledger row
     * for the difference. [reason] is mandatory and kept in the ledger history.
     */
    suspend fun adjustStock(productId: Long, countedMilli: Long, reason: String, now: Long = System.currentTimeMillis()): String? {
        if (reason.isBlank()) return "دلیل اصلاح را بنویسید."
        if (countedMilli < 0) return "مقدار نامعتبر است."
        return db.withTransaction {
            val p = products.get(productId) ?: return@withTransaction "کالا پیدا نشد."
            val delta = countedMilli - p.stockMilli
            if (delta == 0L) return@withTransaction "موجودی تغییری نکرده است."
            ledger.adjust(productId, delta, reason.trim(), now)
            null
        }
    }

    /** How [quantityMilli] of a product is priced if sold now: oldest batch first, each at its own price. */
    suspend fun priceTiers(productId: Long, quantityMilli: Long): List<Fifo.PriceTier> {
        val p = products.get(productId) ?: return emptyList()
        return Fifo.priceTiers(ledger.batches(productId), quantityMilli, p.salePrice)
    }

    /** Batches still in stock, oldest first. */
    suspend fun batchesInStock(productId: Long): List<Fifo.Batch> = ledger.batches(productId).filter { it.remainingMilli > 0 }

    fun observeMovements(productId: Long) = db.inventoryDao().observeForProduct(productId)

    /** Products are archived, never deleted: their history stays in invoices and the ledger. */
    suspend fun setArchived(id: Long, archived: Boolean, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val p = products.get(id) ?: return@withTransaction
        products.update(p.copy(isArchived = archived, updatedAt = now))
        ledger.refreshAlert(p.copy(isArchived = archived), now)
    }
}
