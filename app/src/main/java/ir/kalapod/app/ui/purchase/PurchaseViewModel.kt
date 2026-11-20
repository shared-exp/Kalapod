package ir.kalapod.app.ui.purchase

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
import ir.kalapod.app.data.repository.PurchaseDraft
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

data class PurchaseState(
    val clientUuid: String = UUID.randomUUID().toString(),
    val lines: List<CartLine> = emptyList(),
    val discountText: String = "",
    val extraText: String = "",
    val supplierText: String = "",
    val supplierId: Long? = null,
    val settlement: Settlement = Settlement.CASH,
    val paidNowText: String = "",
    val currency: Currency = Currency.TOMAN,
    val error: String? = null,
    val posting: Boolean = false,
    val lastPosted: PostResult.Posted? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PurchaseViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val products: ProductRepository,
    parties: PartyDao,
    settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PurchaseState())
    val state: StateFlow<PurchaseState> = _state.asStateFlow()

    val pickerQuery = MutableStateFlow("")
    val pickerProducts: StateFlow<List<ProductEntity>> = pickerQuery
        .flatMapLatest { products.observe(it, null, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suppliers: StateFlow<List<Pair<Long, String>>> = parties.observeSuppliers()
        .map { list -> list.map { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { settings.settings.collect { s -> _state.update { it.copy(currency = s.currency) } } }
    }

    fun edit(f: (PurchaseState) -> PurchaseState) = _state.update { f(it).copy(error = null) }

    fun add(p: ProductEntity) = edit { s ->
        s.copy(lines = s.lines.addOrIncrement(CartLine.of(p, p.lastPurchasePrice, s.currency)))
    }

    fun updateLine(index: Int, line: CartLine) = edit { s -> s.copy(lines = s.lines.toMutableList().also { it[index] = line }) }
    fun removeLine(index: Int) = edit { s -> s.copy(lines = s.lines.filterIndexed { i, _ -> i != index }) }

    /** Totals for the summary bar; null while some field is invalid. */
    fun totals(s: PurchaseState): DocumentTotals? {
        val inputs = s.lines.map { it.toInput(s.currency) ?: return null }
        val discount = parseOptional(s.discountText, s.currency) ?: return null
        val extra = parseOptional(s.extraText, s.currency) ?: return null
        if (DocumentCalculator.validate(inputs, discount, extra) != null) return null
        return DocumentCalculator.compute(inputs, discount, extra)
    }

    /** Validates fields; returns an error message, or null if the purchase can be confirmed. */
    fun check(): String? {
        val s = _state.value
        if (s.lines.isEmpty()) return "هیچ کالایی اضافه نشده است."
        s.lines.forEach { l ->
            if (l.quantityMilli() == null) return "مقدار «${l.name}» را درست وارد کنید."
            if (l.unitPrice(s.currency) == null) return "قیمت «${l.name}» را وارد کنید."
        }
        if (parseOptional(s.discountText, s.currency) == null) return "تخفیف نامعتبر است."
        if (parseOptional(s.extraText, s.currency) == null) return "هزینه جانبی نامعتبر است."
        val t = totals(s) ?: return DocumentCalculator.Problem.DiscountTooLarge.message
        if (s.settlement == Settlement.CREDIT) {
            val paid = parseOptional(s.paidNowText, s.currency) ?: return "مبلغ پرداختی نامعتبر است."
            if (paid > t.total) return "مبلغ پرداختی از جمع فاکتور بیشتر است."
            if (s.supplierText.isBlank()) return "برای خرید نسیه نام تأمین‌کننده را وارد کنید."
        }
        return null
    }

    fun showError(msg: String) = _state.update { it.copy(error = msg) }

    fun post() {
        val s = _state.value
        if (s.posting) return
        check()?.let { showError(it); return }
        val t = totals(s)!!
        val paid = when (s.settlement) {
            Settlement.CREDIT -> parseOptional(s.paidNowText, s.currency)!!
            else -> t.total
        }
        val supplier = when {
            s.supplierId != null -> PartyRef.Existing(s.supplierId)
            s.supplierText.isNotBlank() -> PartyRef.New(s.supplierText)
            else -> PartyRef.None
        }
        _state.update { it.copy(posting = true) }
        viewModelScope.launch {
            val r = transactions.postPurchase(
                PurchaseDraft(
                    clientUuid = s.clientUuid,
                    lines = s.lines.map { it.toInput(s.currency)!! },
                    discount = t.discount, extraCost = t.extraCost, supplier = supplier,
                    paidAmount = paid,
                    paidMethod = if (s.settlement == Settlement.CARD) PaymentMethod.CARD else PaymentMethod.CASH,
                ),
            )
            _state.update {
                when (r) {
                    is PostResult.Posted -> PurchaseState(currency = it.currency, lastPosted = r)
                    is PostResult.AlreadyPosted -> PurchaseState(currency = it.currency)
                    is PostResult.Invalid -> it.copy(posting = false, error = r.message)
                    // Purchases add stock; this result is never produced for them.
                    is PostResult.InsufficientStock -> it.copy(posting = false)
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
            val fetched = lines.mapNotNull { l -> products.get(l.product!!.id)?.let { it to l } }
            edit { s ->
                var ls = s.lines
                fetched.forEach { (p, l) ->
                    ls = ls.addOrIncrement(CartLine.of(p, l.unitPrice ?: p.lastPurchasePrice, s.currency, l.quantityMilli))
                }
                s.copy(
                    lines = ls,
                    supplierText = if (s.supplierText.isBlank()) cmd.partyName.orEmpty() else s.supplierText,
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

    private fun parseOptional(text: String, currency: Currency): Long? =
        if (text.isBlank()) 0L else InputParser.money(text, currency)
}
