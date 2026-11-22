package ir.kalapod.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.jalali.JalaliDate
import ir.kalapod.app.data.local.dao.BalanceDao
import ir.kalapod.app.data.local.dao.ProductDao
import ir.kalapod.app.data.local.dao.ReportDao
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.domain.report.Report
import ir.kalapod.app.domain.report.ReportCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class Period(val label: String) { TODAY("امروز"), WEEK("۷ روز اخیر"), MONTH("این ماه"), DAYS30("۳۰ روز اخیر") }

data class ReportsState(
    val period: Period = Period.WEEK,
    val product: ProductEntity? = null,
    val report: Report? = null,
    val stockValue: Long = 0,
    val receivables: Long = 0,
    val payables: Long = 0,
    val currency: Currency = Currency.TOMAN,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportDao,
    private val products: ProductDao,
    private val balances: BalanceDao,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    val pickerQuery = MutableStateFlow("")
    val pickerProducts = pickerQuery.flatMapLatest { products.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(settings.settings, products.observeStockValue(), balances.observeCustomerBalances(), balances.observeSupplierBalances()) { s, v, c, sup ->
                _state.update {
                    it.copy(
                        currency = s.currency, stockValue = v,
                        receivables = c.filter { b -> b.balance > 0 }.sumOf { b -> b.balance },
                        payables = sup.filter { b -> b.balance > 0 }.sumOf { b -> b.balance },
                    )
                }
            }.collect {}
        }
        reload()
    }

    fun setPeriod(p: Period) { _state.update { it.copy(period = p) }; reload() }
    fun setProduct(p: ProductEntity?) { _state.update { it.copy(product = p) }; reload() }

    fun reload() = viewModelScope.launch {
        val s = _state.value
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val from = when (s.period) {
            Period.TODAY -> today
            Period.WEEK -> today.minusDays(6)
            Period.DAYS30 -> today.minusDays(29)
            Period.MONTH -> JalaliDate.of(today).let { JalaliDate(it.year, it.month, 1).toLocalDate() }
        }
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val r = ReportCalculator.build(
            from, today, zone,
            reports.saleLines(fromMs, toMs, s.product?.id),
            reports.docTotals(fromMs, toMs),
            reports.activeProducts(),
            s.product?.id,
        )
        _state.update { it.copy(report = r) }
    }
}
