package ir.kalapod.app.data.repository

import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.InventoryMovementEntity
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.StockAlertEntity
import ir.kalapod.app.data.local.entity.StockAlertType
import ir.kalapod.app.domain.finance.StockPosition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer of stock. Every change goes through a ledger row, then the product's cached
 * stock/value and the open stock alert are refreshed.
 *
 * Callers MUST invoke these from inside `db.withTransaction { }` so the ledger, the cache and the
 * business document are committed atomically.
 */
@Singleton
class InventoryLedger @Inject constructor(private val db: KalapodDatabase) {

    /** Goods in at known total cost (purchase, sale return, opening stock). Returns the movement. */
    suspend fun receive(
        productId: Long, quantityMilli: Long, totalCost: Long, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null,
    ): StockPosition.Movement {
        val p = product(productId)
        val (_, m) = position(p).receive(quantityMilli, totalCost)
        write(p, m, reason, transactionId, at, note)
        return m
    }

    /** Goods out at moving average cost (sale, purchase return). Returns the movement (valueDelta < 0). */
    suspend fun issue(
        productId: Long, quantityMilli: Long, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null,
    ): StockPosition.Movement {
        val p = product(productId)
        val (_, m) = position(p).issue(quantityMilli, fallbackUnitCost = p.lastPurchasePrice)
        write(p, m, reason, transactionId, at, note)
        return m
    }

    /** Manual stock correction; a reason note is mandatory (SPEC §5). */
    suspend fun adjust(productId: Long, deltaMilli: Long, note: String, at: Long): StockPosition.Movement {
        require(note.isNotBlank()) { "adjustment requires a reason" }
        val p = product(productId)
        val (_, m) = position(p).adjust(deltaMilli, fallbackUnitCost = p.lastPurchasePrice)
        write(p, m, MovementReason.ADJUSTMENT, null, at, note)
        return m
    }

    /** Applies an exact pre-computed movement (used by reversals to undo a document precisely). */
    suspend fun applyExact(
        productId: Long, movement: StockPosition.Movement, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null,
    ) = write(product(productId), movement, reason, transactionId, at, note)

    /** Recomputes the cache from the ledger (integrity repair / after restore). */
    suspend fun rebuildCache(productId: Long, at: Long) {
        val inv = db.inventoryDao()
        db.productDao().setStockCache(productId, inv.stockFromLedger(productId), inv.valueFromLedger(productId), at)
        refreshAlert(product(productId), at)
    }

    private suspend fun product(id: Long): ProductEntity =
        db.productDao().get(id) ?: error("product $id not found")

    private fun position(p: ProductEntity) = StockPosition(p.stockMilli, p.stockValue)

    private suspend fun write(
        p: ProductEntity, m: StockPosition.Movement, reason: MovementReason,
        transactionId: Long?, at: Long, note: String?,
    ) {
        db.inventoryDao().insert(
            InventoryMovementEntity(
                productId = p.id, transactionId = transactionId, reason = reason,
                quantityDeltaMilli = m.quantityDeltaMilli, valueDelta = m.valueDelta, note = note, occurredAt = at,
            ),
        )
        val stock = p.stockMilli + m.quantityDeltaMilli
        val value = p.stockValue + m.valueDelta
        db.productDao().setStockCache(p.id, stock, value, at)
        refreshAlert(p.copy(stockMilli = stock, stockValue = value), at)
    }

    /** Keeps at most one open alert per product, matching its current stock level. */
    suspend fun refreshAlert(p: ProductEntity, at: Long) {
        val dao = db.stockAlertDao()
        val wanted = when {
            p.isArchived -> null
            p.stockMilli <= 0 && p.minStockMilli > 0 -> StockAlertType.OUT_OF_STOCK
            p.stockMilli <= 0 && p.minStockMilli == 0L -> null
            p.stockMilli <= p.minStockMilli -> StockAlertType.LOW_STOCK
            else -> null
        }
        val open = dao.openFor(p.id)
        if (open?.type == wanted) return
        if (open != null) dao.resolve(p.id, at)
        if (wanted != null) dao.insert(StockAlertEntity(productId = p.id, type = wanted, createdAt = at))
    }
}
