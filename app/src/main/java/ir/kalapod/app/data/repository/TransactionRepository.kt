package ir.kalapod.app.data.repository

import androidx.room.withTransaction
import ir.kalapod.app.core.jalali.JalaliDate
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.CustomerEntity
import ir.kalapod.app.data.local.entity.InvoiceEntity
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.PaymentEntity
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.SupplierEntity
import ir.kalapod.app.data.local.entity.TransactionEntity
import ir.kalapod.app.data.local.entity.TransactionItemEntity
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.domain.finance.DocumentCalculator
import ir.kalapod.app.domain.finance.LineInput
import ir.kalapod.app.domain.finance.Money
import ir.kalapod.app.domain.finance.StockPosition
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Who the document is with: nobody, an existing party, or a new one to create by name. */
sealed interface PartyRef {
    data object None : PartyRef
    data class Existing(val id: Long) : PartyRef
    data class New(val name: String, val phone: String? = null) : PartyRef
}

data class PurchaseDraft(
    /** Generated when the purchase screen opens; a second post with the same id is ignored. */
    val clientUuid: String,
    val lines: List<LineInput>,
    val discount: Long = 0,
    val extraCost: Long = 0,
    val supplier: PartyRef = PartyRef.None,
    /** Amount paid now. The rest becomes a payable to the supplier. */
    val paidAmount: Long,
    val paidMethod: PaymentMethod = PaymentMethod.CASH,
    val note: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class SaleDraft(
    val clientUuid: String,
    val lines: List<LineInput>,
    val discount: Long = 0,
    val customer: PartyRef = PartyRef.None,
    /** Amount received now (cash or card). The rest becomes the customer's debt (نسیه). */
    val receivedAmount: Long,
    val receivedMethod: PaymentMethod = PaymentMethod.CASH,
    /** True only after the user explicitly confirmed selling more than the recorded stock. */
    val allowOversell: Boolean = false,
    val note: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class ReturnLine(val productId: Long, val quantityMilli: Long)

data class ReturnDraft(
    val clientUuid: String,
    val originalId: Long,
    val lines: List<ReturnLine>,
    /** Money actually given back (sale return) or received back (purchase return) now. */
    val refundAmount: Long,
    val refundMethod: PaymentMethod = PaymentMethod.CASH,
    val note: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class Shortage(val productId: Long, val name: String, val requestedMilli: Long, val availableMilli: Long)

sealed interface PostResult {
    data class Posted(val transactionId: Long, val invoiceNumber: String, val total: Long) : PostResult
    data class AlreadyPosted(val transactionId: Long) : PostResult
    data class Invalid(val message: String) : PostResult
    /** Nothing was written; ask the user, then retry with allowOversell = true. */
    data class InsufficientStock(val shortages: List<Shortage>) : PostResult
}

@Singleton
class TransactionRepository @Inject constructor(
    private val db: KalapodDatabase,
    private val ledger: InventoryLedger,
) {
    suspend fun postPurchase(d: PurchaseDraft): PostResult {
        DocumentCalculator.validate(d.lines, d.discount, d.extraCost)?.let { return PostResult.Invalid(it.message) }
        require(d.paidMethod != PaymentMethod.CREDIT) { "paidMethod must be CASH or CARD" }
        val totals = DocumentCalculator.compute(d.lines, d.discount, d.extraCost)
        if (d.paidAmount < 0 || d.paidAmount > totals.total) return PostResult.Invalid("مبلغ پرداختی نامعتبر است.")
        val unpaid = totals.total - d.paidAmount
        if (unpaid > 0 && d.supplier == PartyRef.None) {
            return PostResult.Invalid("برای خرید نسیه، تأمین‌کننده را مشخص کنید.")
        }

        return db.withTransaction {
            db.transactionDao().byUuid(d.clientUuid)?.let { return@withTransaction PostResult.AlreadyPosted(it.id) }
            val now = System.currentTimeMillis()
            val supplierId = resolveSupplier(d.supplier, now)

            val txId = db.transactionDao().insert(
                TransactionEntity(
                    clientUuid = d.clientUuid, type = TransactionType.PURCHASE, occurredAt = d.occurredAt,
                    supplierId = supplierId, subtotal = totals.subtotal, discount = d.discount,
                    extraCost = d.extraCost, total = totals.total, note = d.note, createdAt = now,
                ),
            )
            db.transactionDao().insertItems(
                d.lines.mapIndexed { i, l ->
                    TransactionItemEntity(
                        transactionId = txId, productId = l.productId, quantityMilli = l.quantityMilli,
                        unitPrice = l.unitPrice, lineTotal = totals.lineTotals[i], costTotal = totals.netLineValues[i],
                    )
                },
            )
            d.lines.forEachIndexed { i, l ->
                ledger.receive(l.productId, l.quantityMilli, totals.netLineValues[i], MovementReason.PURCHASE, txId, d.occurredAt)
                val p = db.productDao().get(l.productId)!!
                db.productDao().update(p.copy(lastPurchasePrice = l.unitPrice, updatedAt = now))
            }
            if (d.paidAmount > 0) {
                db.paymentDao().insert(
                    PaymentEntity(
                        clientUuid = "${d.clientUuid}-pay", transactionId = txId, supplierId = supplierId,
                        direction = PaymentDirection.OUT, method = d.paidMethod, amount = d.paidAmount,
                        occurredAt = d.occurredAt,
                    ),
                )
            }
            val number = issueInvoice(txId, "P", d.occurredAt)
            PostResult.Posted(txId, number, totals.total)
        }
    }

    suspend fun postSale(d: SaleDraft): PostResult {
        DocumentCalculator.validate(d.lines, d.discount, 0)?.let { return PostResult.Invalid(it.message) }
        require(d.receivedMethod != PaymentMethod.CREDIT) { "receivedMethod must be CASH or CARD" }
        val totals = DocumentCalculator.compute(d.lines, d.discount, 0)
        if (d.receivedAmount < 0 || d.receivedAmount > totals.total) return PostResult.Invalid("مبلغ دریافتی نامعتبر است.")
        if (totals.total - d.receivedAmount > 0 && d.customer == PartyRef.None) {
            return PostResult.Invalid("برای فروش نسیه، نام مشتری را وارد کنید.")
        }

        return db.withTransaction {
            db.transactionDao().byUuid(d.clientUuid)?.let { return@withTransaction PostResult.AlreadyPosted(it.id) }

            if (!d.allowOversell) {
                val shortages = d.lines.groupBy { it.productId }.mapNotNull { (pid, ls) ->
                    val p = db.productDao().get(pid) ?: return@withTransaction PostResult.Invalid("کالا پیدا نشد.")
                    val want = ls.sumOf { it.quantityMilli }
                    if (want > p.stockMilli) Shortage(pid, p.name, want, p.stockMilli) else null
                }
                if (shortages.isNotEmpty()) return@withTransaction PostResult.InsufficientStock(shortages)
            }

            val now = System.currentTimeMillis()
            val customerId = resolveCustomer(d.customer, now)
            val txId = db.transactionDao().insert(
                TransactionEntity(
                    clientUuid = d.clientUuid, type = TransactionType.SALE, occurredAt = d.occurredAt,
                    customerId = customerId, subtotal = totals.subtotal, discount = d.discount,
                    total = totals.total, note = d.note, createdAt = now,
                ),
            )
            // Issue stock first so each line records its cost at the moving average.
            val costs = d.lines.map { l ->
                -ledger.issue(l.productId, l.quantityMilli, MovementReason.SALE, txId, d.occurredAt).valueDelta
            }
            db.transactionDao().insertItems(
                d.lines.mapIndexed { i, l ->
                    TransactionItemEntity(
                        transactionId = txId, productId = l.productId, quantityMilli = l.quantityMilli,
                        unitPrice = l.unitPrice, lineTotal = totals.lineTotals[i], costTotal = costs[i],
                    )
                },
            )
            if (d.receivedAmount > 0) {
                db.paymentDao().insert(
                    PaymentEntity(
                        clientUuid = "${d.clientUuid}-pay", transactionId = txId, customerId = customerId,
                        direction = PaymentDirection.IN, method = d.receivedMethod, amount = d.receivedAmount,
                        occurredAt = d.occurredAt,
                    ),
                )
            }
            val number = issueInvoice(txId, "S", d.occurredAt)
            PostResult.Posted(txId, number, totals.total)
        }
    }

    /**
     * Cancels a posted document by writing an exact mirror: opposite ledger movements and opposite
     * payments, linked by reversesId. The original stays in the database marked REVERSED (SPEC §6).
     */
    suspend fun reverse(originalId: Long, reason: String, at: Long = System.currentTimeMillis()): PostResult {
        if (reason.isBlank()) return PostResult.Invalid("دلیل ابطال را بنویسید.")
        return db.withTransaction {
            val dao = db.transactionDao()
            val orig = dao.get(originalId) ?: return@withTransaction PostResult.Invalid("سند پیدا نشد.")
            if (orig.reversesId != null) return@withTransaction PostResult.Invalid("این سند خودش سند ابطال است.")
            val uuid = "reverse-$originalId"
            dao.byUuid(uuid)?.let { return@withTransaction PostResult.AlreadyPosted(it.id) }
            if (orig.status == TransactionStatus.REVERSED) return@withTransaction PostResult.Invalid("این سند قبلاً باطل شده است.")
            if (dao.activeReturnCount(originalId) > 0) {
                return@withTransaction PostResult.Invalid("ابتدا مرجوعی‌های این فاکتور را باطل کنید.")
            }
            val now = System.currentTimeMillis()
            val revId = dao.insert(
                orig.copy(
                    id = 0, clientUuid = uuid, reversesId = orig.id, occurredAt = at, createdAt = now,
                    note = reason.trim(), status = TransactionStatus.POSTED,
                ),
            )
            db.inventoryDao().forTransaction(originalId).forEach { m ->
                ledger.applyExact(
                    m.productId, StockPosition.Movement(-m.quantityDeltaMilli, -m.valueDelta),
                    MovementReason.REVERSAL, revId, at, "ابطال سند",
                )
            }
            db.paymentDao().forTransaction(originalId).filter { it.reversesPaymentId == null }.forEach { p ->
                db.paymentDao().insert(
                    p.copy(
                        id = 0, clientUuid = "${p.clientUuid}-rev", transactionId = revId, reversesPaymentId = p.id,
                        direction = if (p.direction == PaymentDirection.IN) PaymentDirection.OUT else PaymentDirection.IN,
                        occurredAt = at, note = "ابطال",
                    ),
                )
            }
            dao.setStatus(originalId, TransactionStatus.REVERSED)
            PostResult.Posted(revId, db.invoiceDao().forTransaction(originalId)?.number.orEmpty(), orig.total)
        }
    }

    /** Return of part or all of a posted sale or purchase. */
    suspend fun postReturn(d: ReturnDraft): PostResult {
        val lines = d.lines.filter { it.quantityMilli > 0 }
        if (lines.isEmpty()) return PostResult.Invalid("مقدار مرجوعی را وارد کنید.")
        if (d.refundMethod == PaymentMethod.CREDIT) return PostResult.Invalid("روش بازپرداخت نامعتبر است.")
        return db.withTransaction {
            val dao = db.transactionDao()
            dao.byUuid(d.clientUuid)?.let { return@withTransaction PostResult.AlreadyPosted(it.id) }
            val orig = dao.get(d.originalId) ?: return@withTransaction PostResult.Invalid("فاکتور اصلی پیدا نشد.")
            if (orig.status != TransactionStatus.POSTED || orig.reversesId != null) {
                return@withTransaction PostResult.Invalid("این فاکتور باطل شده است.")
            }
            val isSale = when (orig.type) {
                TransactionType.SALE -> true
                TransactionType.PURCHASE -> false
                else -> return@withTransaction PostResult.Invalid("برای مرجوعی نمی‌توان مرجوعی ثبت کرد.")
            }
            val origItems = dao.items(orig.id).groupBy { it.productId }

            data class Calc(val productId: Long, val qty: Long, val lineTotal: Long, val cost: Long, val unitPrice: Long)
            val calcs = lines.map { l ->
                val items = origItems[l.productId] ?: return@withTransaction PostResult.Invalid("این کالا در فاکتور اصلی نیست.")
                val soldQty = items.sumOf { it.quantityMilli }
                val already = dao.returnedQuantity(orig.id, l.productId)
                if (l.quantityMilli > soldQty - already) {
                    val name = db.productDao().get(l.productId)?.name.orEmpty()
                    return@withTransaction PostResult.Invalid("مقدار مرجوعی «$name» از مقدار باقی‌مانده فاکتور بیشتر است.")
                }
                val lineTotal = Money.divRoundHalfUp(items.sumOf { it.lineTotal } * l.quantityMilli, soldQty)
                val cost = Money.divRoundHalfUp(items.sumOf { it.costTotal } * l.quantityMilli, soldQty)
                Calc(l.productId, l.quantityMilli, lineTotal, cost, items.first().unitPrice)
            }
            val subtotal = calcs.sumOf { it.lineTotal }
            // Proportional share of the original invoice discount (sales) / net cost (purchases).
            val discountShare = if (isSale && orig.subtotal > 0) Money.divRoundHalfUp(orig.discount * subtotal, orig.subtotal) else 0L
            val total = if (isSale) subtotal - discountShare else calcs.sumOf { it.cost }
            if (d.refundAmount < 0 || d.refundAmount > total) return@withTransaction PostResult.Invalid("مبلغ بازپرداخت نامعتبر است.")

            if (!isSale) {
                for (c in calcs) {
                    val p = db.productDao().get(c.productId)!!
                    if (p.stockMilli < c.qty) return@withTransaction PostResult.Invalid("موجودی «${p.name}» برای مرجوعی کافی نیست.")
                }
            }

            val now = System.currentTimeMillis()
            val txId = dao.insert(
                TransactionEntity(
                    clientUuid = d.clientUuid,
                    type = if (isSale) TransactionType.SALE_RETURN else TransactionType.PURCHASE_RETURN,
                    occurredAt = d.occurredAt, customerId = orig.customerId, supplierId = orig.supplierId,
                    subtotal = subtotal, discount = discountShare, total = total, originalId = orig.id,
                    note = d.note, createdAt = now,
                ),
            )
            dao.insertItems(
                calcs.map {
                    TransactionItemEntity(
                        transactionId = txId, productId = it.productId, quantityMilli = it.qty,
                        unitPrice = it.unitPrice, lineTotal = it.lineTotal, costTotal = it.cost,
                    )
                },
            )
            calcs.forEach { c ->
                if (isSale) {
                    ledger.receive(c.productId, c.qty, c.cost, MovementReason.SALE_RETURN, txId, d.occurredAt)
                } else {
                    // Goods go back to the supplier at the cost they came in with.
                    ledger.applyExact(c.productId, StockPosition.Movement(-c.qty, -c.cost), MovementReason.PURCHASE_RETURN, txId, d.occurredAt)
                }
            }
            if (d.refundAmount > 0) {
                db.paymentDao().insert(
                    PaymentEntity(
                        clientUuid = "${d.clientUuid}-pay", transactionId = txId,
                        customerId = orig.customerId, supplierId = orig.supplierId,
                        direction = if (isSale) PaymentDirection.OUT else PaymentDirection.IN,
                        method = d.refundMethod, amount = d.refundAmount, occurredAt = d.occurredAt,
                    ),
                )
            }
            val number = issueInvoice(txId, if (isSale) "SR" else "PR", d.occurredAt)
            PostResult.Posted(txId, number, total)
        }
    }

    private suspend fun resolveCustomer(ref: PartyRef, now: Long): Long? = when (ref) {
        PartyRef.None -> null
        is PartyRef.Existing -> ref.id
        is PartyRef.New -> db.partyDao().insertCustomer(CustomerEntity(name = ref.name.trim(), phone = ref.phone, createdAt = now))
    }

    private suspend fun resolveSupplier(ref: PartyRef, now: Long): Long? = when (ref) {
        PartyRef.None -> null
        is PartyRef.Existing -> ref.id
        is PartyRef.New -> db.partyDao().insertSupplier(SupplierEntity(name = ref.name.trim(), phone = ref.phone, createdAt = now))
    }

    /** Sequential per type and Jalali year, e.g. P-1405-000012. Must run inside the posting transaction. */
    private suspend fun issueInvoice(txId: Long, prefix: String, at: Long): String {
        val year = JalaliDate.of(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()).year
        val p = "$prefix-$year-"
        val number = p + (db.invoiceDao().countWithPrefix(p) + 1).toString().padStart(6, '0')
        db.invoiceDao().insert(InvoiceEntity(transactionId = txId, number = number, issuedAt = at))
        return number
    }
}
