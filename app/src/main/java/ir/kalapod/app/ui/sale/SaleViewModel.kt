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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SaleViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    products: ProductRepository,
    parties: PartyDao,
    settings: SettingsRepository,
) : ViewModel() {
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

    fun add(p: ProductEntity) = edit { s -> s.copy(lines = s.lines.addOrIncrement(CartLine.of(p, p.salePrice, s.currency))) }
    fun updateLine(i: Int, line: CartLine) = edit { s -> s.copy(lines = s.lines.toMutableList().also { it[i] = line }) }
    fun removeLine(i: Int) = edit { s -> s.copy(lines = s.lines.filterIndexed { j, _ -> j != i }) }

    /** Per-line hint shown before posting (the repository re-checks atomically). */
    fun stockWarning(line: CartLine, all: List<CartLine>): String? {
        val wanted = all.filter { it.productId == line.productId }.sumOf { it.quantityMilli() ?: 0 }
        return if (wanted > line.stockMilli) "بیش از موجودی" else null
    }

    fun totals(s: SaleState): DocumentTotals? {
        val inputs = s.lines.map { it.toInput(s.currency) ?: return null }
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
            val r = transactions.postSale(
                SaleDraft(
                    clientUuid = s.clientUuid, lines = s.lines.map { it.toInput(s.currency)!! },
                    discount = t.discount, customer = customer, receivedAmount = receivedNow(s, t),
                    receivedMethod = if (s.settlement == Settlement.CARD) PaymentMethod.CARD else PaymentMethod.CASH,
                    allowOversell = allowOversell,
                ),
            )
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

    fun consumePosted() = _state.update { it.copy(lastPosted = null) }

    private fun optional(text: String, currency: Currency): Long? =
        if (text.isBlank()) 0L else InputParser.money(text, currency)
}
