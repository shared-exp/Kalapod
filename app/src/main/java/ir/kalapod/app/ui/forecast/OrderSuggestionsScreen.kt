package ir.kalapod.app.ui.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.repository.ForecastRepository
import ir.kalapod.app.domain.forecast.Confidence
import ir.kalapod.app.domain.forecast.ProductForecast
import ir.kalapod.app.domain.forecast.Trend
import ir.kalapod.app.ui.common.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/** Hands a list of (productId, quantityMilli) from the suggestions screen to the purchase screen. */
@Singleton
class PendingPurchase @Inject constructor() {
    @Volatile var items: List<Pair<Long, Long>>? = null
    /** Optional unit purchase price per product (rial), e.g. the new price of a new batch. */
    @Volatile var prices: Map<Long, Long> = emptyMap()
    /** Optional sale price of the new batch per product (rial). */
    @Volatile var salePrices: Map<Long, Long> = emptyMap()
}

data class SuggestionRow(val forecast: ProductForecast, val selected: Boolean, val qtyText: String)

@HiltViewModel
class OrderSuggestionsViewModel @Inject constructor(
    private val repo: ForecastRepository,
    private val pending: PendingPurchase,
) : ViewModel() {
    private val _rows = MutableStateFlow<List<SuggestionRow>?>(null)
    val rows = _rows.asStateFlow()

    init {
        viewModelScope.launch {
            _rows.value = repo.all().map { SuggestionRow(it, it.shouldOrder, if (it.shouldOrder) InputParser.quantityForEdit(it.suggestedOrderMilli) else "") }
        }
    }

    fun toggle(id: Long) = _rows.update { l -> l?.map { if (it.forecast.productId == id) it.copy(selected = !it.selected) else it } }
    fun setQty(id: Long, t: String) = _rows.update { l -> l?.map { if (it.forecast.productId == id) it.copy(qtyText = t, selected = true) else it } }

    /** Returns true if something was handed over to the purchase screen. */
    fun sendToPurchase(): Boolean {
        val items = _rows.value.orEmpty().filter { it.selected }
            .mapNotNull { r -> InputParser.quantity(r.qtyText)?.takeIf { it > 0 }?.let { r.forecast.productId to it } }
        if (items.isEmpty()) return false
        pending.items = items
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderSuggestionsScreen(onBack: () -> Unit, onGoToPurchase: () -> Unit, vm: OrderSuggestionsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("پیشنهاد خرید") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        bottomBar = {
            val n = rows.orEmpty().count { it.selected }
            if (n > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = { if (vm.sendToPurchase()) onGoToPurchase() },
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
                    ) { Text("افزودن ${PersianFormat.number(n.toLong())} قلم به فاکتور خرید", style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val list = rows
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val toOrder = list.filter { it.forecast.shouldOrder }
        val rest = list.filterNot { it.forecast.shouldOrder }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    if (toOrder.isEmpty()) "فعلاً کالایی نیاز به سفارش ندارد."
                    else "این کالاها با سرعت فروش فعلی و زمان تأمین، به‌زودی تمام می‌شوند:",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(toOrder, key = { it.forecast.productId }) { ForecastCard(it, vm) }
            if (rest.isNotEmpty()) {
                item { Text("سایر کالاها", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(rest, key = { it.forecast.productId }) { ForecastCard(it, vm) }
            }
        }
    }
}

@Composable
private fun ForecastCard(r: SuggestionRow, vm: OrderSuggestionsViewModel) {
    val f = r.forecast
    val unit = f.unit.label
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(r.selected, onCheckedChange = { vm.toggle(f.productId) })
                Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("موجودی: ${PersianFormat.quantity(f.stockMilli)} $unit", style = MaterialTheme.typography.bodySmall)
            }
            if (f.confidence == Confidence.INSUFFICIENT) {
                Text(f.note.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "فروش روزانه: ${PersianFormat.quantity(f.avgDailyMilli / 100 * 100)} $unit  ·  هفتگی: ${PersianFormat.quantity(f.avgWeeklyMilli / 100 * 100)} $unit",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val stockout = f.daysToStockout?.let { d ->
                    when {
                        d < 1 -> "امروز تمام می‌شود"
                        d > 90 -> "بیش از ۳ ماه کافی است"
                        else -> "حدود ${PersianFormat.number(d.toLong())} روز دیگر تمام می‌شود"
                    }
                } ?: "فروشی ندارد"
                Text(
                    stockout + trendText(f.trend) + (f.busiestDay?.let { "  ·  پرفروش‌ترین روز: ${dayName(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((f.daysToStockout ?: 99.0) <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                f.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            }
            if (r.selected || f.shouldOrder) {
                OutlinedTextField(
                    r.qtyText, { vm.setQty(f.productId, it) }, singleLine = true,
                    label = { Text("مقدار خرید ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
}

private fun trendText(t: Trend) = when (t) {
    Trend.UP -> "  ·  فروش رو به افزایش"
    Trend.DOWN -> "  ·  فروش رو به کاهش"
    else -> ""
}

private fun dayName(d: DayOfWeek) = when (d) {
    DayOfWeek.SATURDAY -> "شنبه"; DayOfWeek.SUNDAY -> "یکشنبه"; DayOfWeek.MONDAY -> "دوشنبه"
    DayOfWeek.TUESDAY -> "سه‌شنبه"; DayOfWeek.WEDNESDAY -> "چهارشنبه"; DayOfWeek.THURSDAY -> "پنجشنبه"; DayOfWeek.FRIDAY -> "جمعه"
}
