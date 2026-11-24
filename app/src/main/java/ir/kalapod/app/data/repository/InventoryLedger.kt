package ir.kalapod.app.data.repository

import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.InventoryMovementEntity
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.StockAlertEntity
import ir.kalapod.app.data.local.entity.StockAlertType
import ir.kalapod.app.domain.finance.Fifo
import ir.kalapod.app.domain.finance.Money
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

    /**
     * Goods in at a known total cost. Without [intoBatch] this is a new batch (purchase, opening stock)
     * priced at [salePrice] (default: the product's current price); with it, the goods go back into that
     * batch (a sale return puts goods back where they came from). Returns the net movement.
     */
    suspend fun receive(
        productId: Long, quantityMilli: Long, totalCost: Long, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null, salePrice: Long? = null, intoBatch: Long? = null,
    ): StockPosition.Movement {
        val p = product(productId)
        val (_, m) = StockPosition().receive(quantityMilli, totalCost)
        if (intoBatch != null) {
            insert(p.id, m, reason, transactionId, at, note, intoBatch)
            return refresh(p.id, at).let { m }
        }
        val head = insert(p.id, m, reason, transactionId, at, note, batchId = null)
        db.inventoryDao().makeBatchHead(head, salePrice ?: p.salePrice)
        // Goods sold before they were in stock (a knowingly confirmed oversell) come out of this batch now.
        var batch = StockPosition(quantityMilli, totalCost)
        for (b in batches(p.id)) {
            if (b.id == head || b.remainingMilli >= 0 || batch.quantityMilli <= 0) continue
            val (rest, out) = batch.issue(minOf(-b.remainingMilli, batch.quantityMilli), fallbackUnitCost = 0)
            insert(p.id, out, reason, transactionId, at, note, head)
            insert(p.id, StockPosition.Movement(-out.quantityDeltaMilli, -out.valueDelta), reason, transactionId, at, note, b.id)
            settleOversell(b.id, p.id)
            batch = rest
        }
        refresh(p.id, at)
        return m
    }

    /** Goods out, oldest batch first (sale, stock count shortfall). Returns the net movement (valueDelta < 0). */
    suspend fun issue(
        productId: Long, quantityMilli: Long, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null,
    ): StockPosition.Movement {
        val p = product(productId)
        val takes = Fifo.take(batches(p.id), quantityMilli, fallbackUnitCost = p.lastPurchasePrice)
        takes.forEach { t ->
            insert(p.id, StockPosition.Movement(-t.quantityMilli, -t.cost), reason, transactionId, at, note, t.batchId)
                .also { id -> if (t.batchId == null) db.inventoryDao().makeBatchHead(id, p.salePrice) }
        }
        refresh(p.id, at)
        return StockPosition.Movement(-quantityMilli, -takes.sumOf { it.cost })
    }

    /** Manual stock correction; a reason note is mandatory (SPEC §5). Extra goods join the batch on sale. */
    suspend fun adjust(productId: Long, deltaMilli: Long, note: String, at: Long): StockPosition.Movement {
        require(note.isNotBlank()) { "adjustment requires a reason" }
        require(deltaMilli != 0L) { "delta must be non-zero" }
        if (deltaMilli < 0) return issue(productId, -deltaMilli, MovementReason.ADJUSTMENT, null, at, note)
        val p = product(productId)
        val front = Fifo.front(batches(p.id))
        val unitCost = front?.let { StockPosition(it.remainingMilli, it.remainingValue).averageUnitCost } ?: p.lastPurchasePrice
        val cost = Money.lineTotal(unitCost, deltaMilli)
        return if (front != null) {
            receive(p.id, deltaMilli, cost, MovementReason.ADJUSTMENT, null, at, note, intoBatch = front.id)
        } else {
            receive(p.id, deltaMilli, cost, MovementReason.ADJUSTMENT, null, at, note)
        }
    }

    /**
     * Applies an exact pre-computed movement to a given batch (reversals mirror each movement into its own
     * batch; a purchase return takes from the batch the purchase brought in). Null batch = a batch of its own.
     */
    suspend fun applyExact(
        productId: Long, movement: StockPosition.Movement, reason: MovementReason,
        transactionId: Long?, at: Long, note: String? = null, batchId: Long? = null,
    ) {
        val id = insert(productId, movement, reason, transactionId, at, note, batchId)
        if (batchId == null) db.inventoryDao().makeBatchHead(id, product(productId).salePrice)
        refresh(productId, at)
    }

    /**
     * Sale return: goods go back into the batches [soldFrom] took them from (newest first), each piece at
     * the cost it left with ([alreadyReturned] = units of that sale returned before). Anything beyond becomes a batch of its own at [fallbackCost]. Returns the cost.
     */
    suspend fun returnFromCustomer(
        productId: Long, qty: Long, fallbackCost: Long, soldFrom: List<InventoryMovementEntity>, txId: Long, at: Long,
        alreadyReturned: Long = 0,
    ): Long {
        var left = qty
        var skip = alreadyReturned       // earlier returns of the same sale already went back to the newest pieces
        var total = 0L
        for (m in soldFrom.filter { it.quantityDeltaMilli < 0 }.asReversed()) {
            if (left == 0L) break
            val skipped = minOf(skip, -m.quantityDeltaMilli)
            skip -= skipped
            val q = minOf(left, -m.quantityDeltaMilli - skipped)
            if (q == 0L) continue
            val part = Money.divRoundHalfUp(-m.valueDelta * q, -m.quantityDeltaMilli)
            receive(productId, q, part, MovementReason.SALE_RETURN, txId, at, intoBatch = m.batchId)
            left -= q; total += part
        }
        if (left > 0) {
            val part = Money.divRoundHalfUp(fallbackCost * left, qty)
            receive(productId, left, part, MovementReason.SALE_RETURN, txId, at)
            total += part
        }
        return total
    }

    /**
     * Purchase return: goods leave the batch [purchaseMoves] brought in, at [cost]; if that batch has
     * already been partly sold, the rest comes from the oldest stock.
     */
    suspend fun returnToSupplier(
        productId: Long, qty: Long, cost: Long, purchaseMoves: List<InventoryMovementEntity>, txId: Long, at: Long,
    ) {
        val head = purchaseMoves.firstOrNull { it.batchId == it.id }?.id
        val inBatch = head?.let { h -> batches(productId).firstOrNull { it.id == h }?.remainingMilli } ?: 0L
        val own = minOf(qty, inBatch.coerceAtLeast(0))
        if (own > 0) {
            val ownCost = Money.divRoundHalfUp(cost * own, qty)
            applyExact(productId, StockPosition.Movement(-own, -ownCost), MovementReason.PURCHASE_RETURN, txId, at, batchId = head)
        }
        if (qty > own) issue(productId, qty - own, MovementReason.PURCHASE_RETURN, txId, at)
    }

    /**
     * Goods sold before they were recorded were costed at a guess (last purchase price, maybe 0). Once a
     * purchase covers them, the batch is back at zero quantity but keeps the guess error as leftover value;
     * that difference is moved onto the sales (and their invoice lines) that oversold, so their cost and
     * profit become the real ones. Split over those sales by the quantity each oversold; exact to the rial.
     */
    private suspend fun settleOversell(batchId: Long, productId: Long) {
        val dao = db.inventoryDao()
        val moves = dao.inBatch(batchId)
        if (moves.sumOf { it.quantityDeltaMilli } != 0L) return
        val residual = moves.sumOf { it.valueDelta }
        if (residual == 0L) return
        // The part of each outgoing movement that took the batch below zero.
        val over = mutableListOf<Pair<InventoryMovementEntity, Long>>()
        var running = 0L
        for (m in moves) {
            val before = running
            running += m.quantityDeltaMilli
            if (m.quantityDeltaMilli < 0) {
                val q = maxOf(0L, -running) - maxOf(0L, -before)
                if (q > 0) over += m to q
            }
        }
        val total = over.sumOf { it.second }
        if (total == 0L) return
        var left = residual
        over.forEachIndexed { k, (m, q) ->
            val part = if (k == over.lastIndex) left else Money.divRoundHalfUp(residual * q, total)
            left -= part
            dao.addValue(m.id, -part)
            if (m.transactionId != null && m.reason == MovementReason.SALE) {
                dao.lastItem(m.transactionId, productId)?.let { dao.addItemCost(it, part) }
            }
        }
    }

    /** Batches of a product, oldest first. */
    suspend fun batches(productId: Long): List<Fifo.Batch> =
        db.inventoryDao().batches(productId).map { Fifo.Batch(it.batchId, it.remainingMilli, it.remainingValue, it.salePrice) }

    /** Recomputes the cache from the ledger (integrity repair / after restore). */
    suspend fun rebuildCache(productId: Long, at: Long) = refresh(productId, at)

    private suspend fun product(id: Long): ProductEntity =
        db.productDao().get(id) ?: error("product $id not found")

    private suspend fun insert(
        productId: Long, m: StockPosition.Movement, reason: MovementReason,
        transactionId: Long?, at: Long, note: String?, batchId: Long?,
    ): Long = db.inventoryDao().insert(
        InventoryMovementEntity(
            productId = productId, transactionId = transactionId, reason = reason,
            quantityDeltaMilli = m.quantityDeltaMilli, valueDelta = m.valueDelta, note = note, occurredAt = at,
            batchId = batchId,
        ),
    )

    /** Cache = ledger sums; the shelf price = the price of the batch now on sale. */
    private suspend fun refresh(productId: Long, at: Long) {
        val inv = db.inventoryDao()
        db.productDao().setStockCache(productId, inv.stockFromLedger(productId), inv.valueFromLedger(productId), at)
        var p = product(productId)
        val price = Fifo.front(batches(productId))?.salePrice
        if (price != null && price != p.salePrice) {
            p = p.copy(salePrice = price, updatedAt = at)
            db.productDao().update(p)
        }
        refreshAlert(p, at)
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
