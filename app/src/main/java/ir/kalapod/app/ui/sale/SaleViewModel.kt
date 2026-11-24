package ir.kalapod.app.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.data.local.dao.PartyDao
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.repository.PartyRef
import ir.kalapod.app.data.repository.PostResult
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.SaleDraft
import ir.kalapod.app.data.repository.Shortage
import ir.kalapod.app.data.repository.TransactionRepository
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.domain.finance.DocumentCalculator
import ir.kalapod.app.domain.finance.DocumentTotals
import ir.kalapod.app.ui.cart.CartLine
import ir.kalapod.app.ui.cart.Settlement
import ir.kalapod.app.ui.cart.addOrIncrement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SaleState(
    val clientUuid: String = UUID.randomUUID().toString(),
    val lines: List<CartLine> = emptyList(),
    val discountText: String = "",
    val customerText: String = "",
    val customerId: Long? = null,
    val settlement: Settlement = Settlement.CASH,
    val receivedNowText: String = "",
    val currency: Currency = Currency.TOMAN,
    val error: String? = null,
    val posting: Boolean = false,
    /** Set when the repository refused for low stock; the UI asks for explicit confirmation. */
    val shortages: List<Shortage> = emptyList(),
    val lastPosted: PostResult.Posted? = null,
    /** Editing a posted sale: saving cancels it and posts the corrected one in its place. */
    val editingId: Long? = null,
    val editingNumber: String? = null,
    val editingAt: Long? = null,
)

/** Hands a sale to edit from the invoice page to the sale screen (once). */
@javax.inject.Singleton
class PendingSaleEdit @Inject constructor() { @Volatile var id: Long? = null }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SaleViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val products: ProductRepository,
    parties: PartyDao,
    settings: SettingsRepository,
    transactionDao: ir.kalapod.app.data.local.dao.TransactionDao,
    private val pendingEdit: PendingSaleEdit,
) : ViewModel() {
    /** Sales history shown under the sale screen (newest first). */
    val recentSales: StateFlow<List<ir.kalapod.app.data.local.dao.DocumentRow>> =
        transactionDao.observeDocuments(ir.kalapod.app.data.local.entity.TransactionType.SALE, 200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun consumePendingEdit() { pendingEdit.id?.let { pendingEdit.id = null; startEdit(it) } }

    /** Loads a posted sale into the cart as it was entered. */
    fun startEdit(id: Long) {
        viewModelScope.launch {
            val e = transactions.saleForEdit(id) ?: run { showError("این فاکتور قابل ویرایش نیست."); return@launch }
            val cur = _state.value.currency
            val lines = e.lines.mapNotNull { l -> products.get(l.productId)?.let { CartLine.of(it, l.unitPrice, cur, l.quantityMilli) } }
            val credit = e.received < e.total
            _state.value = SaleState(
                currency = cur, lines = lines,
                discountText = if (e.discount > 0) ir.kalapod.app.core.format.InputParser.moneyForEdit(e.discount, cur) else "",
                customerText = e.customerName.orEmpty(), customerId = e.customerId,
                settlement = when {
                    credit -> Settlement.CREDIT
                    e.receivedMethod == PaymentMethod.CARD -> Settlement.CARD
                    else -> Settlement.CASH
                },
                receivedNowText = if (credit && e.received > 0) ir.kalapod.app.core.format.InputParser.moneyForEdit(e.received, cur) else "",
                editingId = e.originalId, editingNumber = e.invoiceNumber, editingAt = e.occurredAt,
            )
        }
    }

    fun cancelEdit() = _state.update { SaleState(currency = it.currency) }

    private val _state = MutableStateFlow(SaleState())
    val state: StateFlow<SaleState> = _state.asStateFlow()

    val pickerQuery = MutableStateFlow("")
    val pickerProducts: StateFlow<List<ProductEntity>> = pickerQuery
        .flatMapLatest { products.observe(it, null, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customers: StateFlow<List<Pair<Long, String>>> = parties.observeCustomers()
        .map { list -> list.map { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { settings.settings.collect { s -> _state.update { it.copy(currency = s.currency) } } }
    }

    fun edit(f: (SaleState) -> SaleState) = _state.update { f(it).copy(error = null) }

    fun add(p: ProductEntity) {
        viewModelScope.launch {
            val batches = products.batchesInStock(p.id)
            edit { s -> s.copy(lines = s.lines.addOrIncrement(CartLine.of(p, p.salePrice, s.currency, batches = batches))) }
        }
    }
    fun updateLine(i: Int, line: CartLine) = edit { s -> s.copy(lines = s.lines.toMutableList().also { it[i] = line }) }
    fun removeLine(i: Int) = edit { s -> s.copy(lines = s.lines.filterIndexed { j, _ -> j != i }) }

    /** Per-line hint shown before posting (the repository re-checks atomically). */
    fun stockWarning(line: CartLine, all: List<CartLine>): String? {
        val wanted = all.filter { it.productId == line.productId }.sumOf { it.quantityMilli() ?: 0 }
        return if (wanted > line.stockMilli) "بیش از موجودی" else null
    }

    fun totals(s: SaleState): DocumentTotals? {
        val inputs = s.lines.flatMap { it.toInputs(s.currency) ?: return null }
        val discount = optional(s.discountText, s.currency) ?: return null
        if (DocumentCalculator.validate(inputs, discount, 0) != null) return null
        return DocumentCalculator.compute(inputs, discount, 0)
    }

    fun check(): String? {
        val s = _state.value
        if (s.lines.isEmpty()) return "هیچ کالایی اضافه نشده است."
        s.lines.forEach { l ->
            if (l.quantityMilli() == null) return "مقدار «${l.name}» را درست وارد کنید."
            if (l.unitPrice(s.currency) == null) return "قیمت «${l.name}» را وارد کنید."
        }
        if (optional(s.discountText, s.currency) == null) return "تخفیف نامعتبر است."
        val t = totals(s) ?: return DocumentCalculator.Problem.DiscountTooLarge.message
        if (s.settlement == Settlement.CREDIT) {
            val got = optional(s.receivedNowText, s.currency) ?: return "مبلغ دریافتی نامعتبر است."
            if (got > t.total) return "مبلغ دریافتی از جمع فاکتور بیشتر است."
            if (s.customerText.isBlank()) return "برای فروش نسیه نام مشتری را وارد کنید."
        }
        return null
    }

    fun receivedNow(s: SaleState, t: DocumentTotals): Long =
        if (s.settlement == Settlement.CREDIT) optional(s.receivedNowText, s.currency) ?: 0 else t.total

    fun showError(msg: String) = _state.update { it.copy(error = msg) }
    fun dismissShortages() = _state.update { it.copy(shortages = emptyList()) }

    fun post(allowOversell: Boolean = false) {
        val s = _state.value
        if (s.posting) return
        check()?.let { showError(it); return }
        val t = totals(s)!!
        val customer = when {
            s.customerId != null -> PartyRef.Existing(s.customerId)
            s.customerText.isNotBlank() -> PartyRef.New(s.customerText)
            else -> PartyRef.None
        }
        _state.update { it.copy(posting = true, shortages = emptyList()) }
        viewModelScope.launch {
            val draft = SaleDraft(
                clientUuid = s.clientUuid, lines = s.lines.flatMap { it.toInputs(s.currency)!! },
                discount = t.discount, customer = customer, receivedAmount = receivedNow(s, t),
                receivedMethod = if (s.settlement == Settlement.CARD) PaymentMethod.CARD else PaymentMethod.CASH,
                allowOversell = allowOversell,
                occurredAt = s.editingAt ?: System.currentTimeMillis(),
            )
            val r = if (s.editingId != null) transactions.correctSale(s.editingId, draft) else transactions.postSale(draft)
            _state.update {
                when (r) {
                    is PostResult.Posted -> SaleState(currency = it.currency, lastPosted = r)
                    is PostResult.AlreadyPosted -> SaleState(currency = it.currency)
                    is PostResult.Invalid -> it.copy(posting = false, error = r.message)
                    is PostResult.InsufficientStock -> it.copy(posting = false, shortages = r.shortages)
                }
            }
        }
    }

    /** Feedback line shown inside the scanner. */
    val scanFeedback = MutableStateFlow("")
    /** A scanned code with no product yet; the screen offers to define it. */
    val unknownBarcode = MutableStateFlow<String?>(null)

    fun onBarcode(code: String) {
        viewModelScope.launch {
            val p = products.findByBarcode(code)
            if (p == null) {
                scanFeedback.value = "بارکد $code ثبت نشده است"
                unknownBarcode.value = code
            } else {
                add(p)
                val q = _state.value.lines.firstOrNull { it.productId == p.id }?.quantityText.orEmpty()
                scanFeedback.value = "${p.name} — تعداد: ${ir.kalapod.app.core.format.PersianFormat.digits(q)}"
            }
        }
    }

    /** Adds confirmed voice lines; also takes the spoken party/payment if the user hasn't set them. */
    fun applyVoice(lines: List<ir.kalapod.app.domain.voice.ResolvedLine>, cmd: ir.kalapod.app.domain.voice.ParsedCommand) {
        viewModelScope.launch {
            val fetched = lines.mapNotNull { l -> products.get(l.product!!.id)?.let { Triple(it, l, products.batchesInStock(it.id)) } }
            edit { s ->
                var ls = s.lines
                fetched.forEach { (p, l, batches) ->
                    ls = ls.addOrIncrement(CartLine.of(p, l.unitPrice ?: p.salePrice, s.currency, l.quantityMilli, batches))
                }
                s.copy(
                    lines = ls,
                    customerText = if (s.customerText.isBlank()) cmd.partyName.orEmpty() else s.customerText,
                    settlement = when (cmd.payment) {
                        ir.kalapod.app.domain.voice.VoicePayment.CREDIT -> Settlement.CREDIT
                        ir.kalapod.app.domain.voice.VoicePayment.CARD -> Settlement.CARD
                        ir.kalapod.app.domain.voice.VoicePayment.CASH -> Settlement.CASH
                        null -> s.settlement
                    },
                )
            }
        }
    }

    fun consumePosted() = _state.update { it.copy(lastPosted = null) }

    private fun optional(text: String, currency: Currency): Long? =
        if (text.isBlank()) 0L else InputParser.money(text, currency)
}
