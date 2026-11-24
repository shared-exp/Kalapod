package ir.kalapod.app.ui.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.dao.ItemRow
import ir.kalapod.app.data.local.entity.PaymentEntity
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.TransactionEntity
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.data.repository.PostResult
import ir.kalapod.app.data.repository.ReturnDraft
import ir.kalapod.app.data.repository.ReturnLine
import ir.kalapod.app.data.repository.TransactionRepository
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.domain.finance.Money
import ir.kalapod.app.ui.common.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ReturnableLine(val item: ItemRow, val remainingMilli: Long, val quantityText: String = "")

data class DocumentDetail(
    val tx: TransactionEntity,
    val invoiceNumber: String?,
    val partyName: String?,
    val items: List<ItemRow>,
    val payments: List<PaymentEntity>,
    val returns: List<TransactionEntity>,
    val original: TransactionEntity?,
    val reversal: TransactionEntity?,
    val returnable: List<ReturnableLine>,
) {
    val canReturn get() = tx.status == TransactionStatus.POSTED &&
        (tx.type == TransactionType.SALE || tx.type == TransactionType.PURCHASE) && returnable.any { it.remainingMilli > 0 }
    val canReverse get() = tx.status == TransactionStatus.POSTED && returns.none { it.status == TransactionStatus.POSTED }
}

data class DetailState(
    val detail: DocumentDetail? = null,
    val currency: Currency = Currency.TOMAN,
    val returnLines: List<ReturnableLine> = emptyList(),
    val refundText: String = "",
    val refundMethod: PaymentMethod = PaymentMethod.CASH,
    val returnUuid: String = UUID.randomUUID().toString(),
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val db: KalapodDatabase,
    private val repo: TransactionRepository,
    private val settings: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val pendingEdit: ir.kalapod.app.ui.sale.PendingSaleEdit,
) : ViewModel() {
    /** Opens this sale in the sale screen for editing. */
    fun requestEdit() { pendingEdit.id = id }

    private val id: Long = savedState["id"] ?: 0L
    /** Opened from «ثبت مرجوعی»: show the return form right away (once). */
    var startReturnOnOpen: Boolean = savedState["startReturn"] ?: false
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init { reload() }

    fun reload() = viewModelScope.launch {
        val cur = settings.settings.first().currency
        val dao = db.transactionDao()
        val tx = dao.get(id) ?: return@launch
        val items = dao.itemsWithProduct(id)
        val party = tx.customerId?.let { cid -> db.partyDao().observeCustomers().first().find { it.id == cid }?.name }
            ?: tx.supplierId?.let { sid -> db.partyDao().observeSuppliers().first().find { it.id == sid }?.name }
        val returnable = items.groupBy { it.item.productId }.map { (pid, rows) ->
            val sold = rows.sumOf { it.item.quantityMilli }
            ReturnableLine(rows.first(), sold - dao.returnedQuantity(id, pid))
        }
        val detail = DocumentDetail(
            tx = tx, invoiceNumber = db.invoiceDao().forTransaction(id)?.number, partyName = party, items = items,
            payments = db.paymentDao().forTransaction(id), returns = dao.returnsOf(id),
            original = tx.originalId?.let { dao.get(it) },
            reversal = if (tx.status == TransactionStatus.REVERSED) dao.byUuid("reverse-$id") else null,
            returnable = returnable,
        )
        _state.update { it.copy(detail = detail, currency = cur, returnLines = returnable) }
    }

    fun startReturn() = _state.update {
        it.copy(returnLines = it.detail?.returnable.orEmpty(), refundText = "", returnUuid = UUID.randomUUID().toString(), error = null)
    }

    fun setReturnQty(i: Int, text: String) = _state.update { s ->
        s.copy(returnLines = s.returnLines.toMutableList().also { it[i] = it[i].copy(quantityText = text) }, error = null)
    }

    /** Fills return quantities from voice; items not on this invoice are reported, not guessed. */
    fun applyVoiceToReturn(lines: List<ir.kalapod.app.domain.voice.ResolvedLine>) = _state.update { s ->
        var rl = s.returnLines
        val missing = mutableListOf<String>()
        lines.forEach { l ->
            val i = rl.indexOfFirst { it.item.item.productId == l.product?.id }
            if (i < 0) missing += (l.product?.name ?: l.heard.name)
            else rl = rl.toMutableList().also { it[i] = it[i].copy(quantityText = InputParser.quantityForEdit(l.quantityMilli)) }
        }
        s.copy(
            returnLines = rl,
            error = if (missing.isEmpty()) null else missing.joinToString("، ") { "«$it»" } + " در این فاکتور نیست.",
        )
    }

    fun setRefund(text: String) = _state.update { it.copy(refundText = text, error = null) }
    fun setRefundMethod(m: PaymentMethod) = _state.update { it.copy(refundMethod = m) }

    /** Value of the return as currently entered, using the same rules as the repository. */
    fun estimatedReturnValue(s: DetailState): Long {
        val d = s.detail ?: return 0
        val gross = s.returnLines.sumOf { l ->
            val q = InputParser.quantity(l.quantityText) ?: 0
            if (l.item.item.quantityMilli == 0L) 0 else Money.divRoundHalfUp(l.item.item.lineTotal * q, l.item.item.quantityMilli)
        }
        return if (d.tx.type == TransactionType.SALE && d.tx.subtotal > 0) {
            gross - Money.divRoundHalfUp(d.tx.discount * gross, d.tx.subtotal)
        } else if (d.tx.type == TransactionType.PURCHASE) {
            // Purchase returns are valued at net cost (discount and extra cost included).
            s.returnLines.sumOf { l ->
                val q = InputParser.quantity(l.quantityText) ?: 0
                if (l.item.item.quantityMilli == 0L) 0 else Money.divRoundHalfUp(l.item.item.costTotal * q, l.item.item.quantityMilli)
            }
        } else gross
    }

    /** Returns true when posted. */
    suspend fun submitReturn(): Boolean {
        val s = _state.value
        val lines = s.returnLines.mapNotNull { l ->
            if (l.quantityText.isBlank()) return@mapNotNull null
            val q = InputParser.quantity(l.quantityText) ?: run { fail("مقدار «${l.item.productName}» نامعتبر است."); return false }
            ReturnLine(l.item.item.productId, q)
        }
        val refund = if (s.refundText.isBlank()) 0L else InputParser.money(s.refundText, s.currency)
            ?: run { fail("مبلغ بازپرداخت نامعتبر است."); return false }
        return when (val r = repo.postReturn(ReturnDraft(s.returnUuid, id, lines, refund, s.refundMethod))) {
            is PostResult.Posted, is PostResult.AlreadyPosted -> { done("مرجوعی ثبت شد."); true }
            is PostResult.Invalid -> { fail(r.message); false }
            is PostResult.InsufficientStock -> { fail("موجودی کافی نیست."); false }
        }
    }

    suspend fun submitReverse(reason: String): Boolean = when (val r = repo.reverse(id, reason)) {
        is PostResult.Posted, is PostResult.AlreadyPosted -> { done("سند باطل شد."); true }
        is PostResult.Invalid -> { fail(r.message); false }
        is PostResult.InsufficientStock -> false
    }

    /** Builds (or rebuilds) this document's PDF in the cache and returns it. */
    suspend fun pdf(): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val d = _state.value.detail ?: error("not loaded")
        val st = settings.settings.first()
        val isPurchaseSide = d.tx.type == TransactionType.PURCHASE || d.tx.type == TransactionType.PURCHASE_RETURN
        val paid = d.payments.sumOf { if ((it.direction == ir.kalapod.app.data.local.entity.PaymentDirection.IN) != isPurchaseSide) it.amount else -it.amount }
            .let { if (d.tx.type == TransactionType.SALE_RETURN || d.tx.type == TransactionType.PURCHASE_RETURN) -it else it }
        val data = ir.kalapod.app.data.pdf.InvoiceData(
            shopName = st.shopName,
            title = "فاکتور " + d.tx.type.label,
            number = d.invoiceNumber.orEmpty(),
            dateText = ir.kalapod.app.ui.common.formatDateTime(d.tx.occurredAt),
            partyLabel = if (isPurchaseSide) "تأمین‌کننده" else "مشتری",
            partyName = d.partyName,
            lines = d.items.map {
                ir.kalapod.app.data.pdf.InvoiceData.Line(it.productName, it.item.quantityMilli, it.unit.label, it.item.unitPrice, it.item.lineTotal, it.item.caseSizeMilli)
            },
            subtotal = d.tx.subtotal, discount = d.tx.discount, extraCost = d.tx.extraCost, total = d.tx.total,
            paid = paid.coerceIn(0, d.tx.total), reversed = d.tx.status == TransactionStatus.REVERSED, currency = st.currency,
        )
        val name = (d.invoiceNumber ?: "doc-${d.tx.id}").replace(Regex("[^A-Za-z0-9-]"), "_")
        ir.kalapod.app.data.pdf.InvoicePdf.write(appContext, data, java.io.File(appContext.cacheDir, "invoices/$name.pdf"))
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun fail(msg: String) = _state.update { it.copy(error = msg) }
    private fun done(msg: String) { _state.update { it.copy(message = msg, error = null) }; reload() }
}
