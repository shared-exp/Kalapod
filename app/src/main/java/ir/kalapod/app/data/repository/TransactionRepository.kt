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
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.domain.finance.DocumentCalculator
import ir.kalapod.app.domain.finance.LineInput
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
