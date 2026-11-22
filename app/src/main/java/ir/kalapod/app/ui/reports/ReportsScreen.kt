package ir.kalapod.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.core.jalali.JalaliDate
import ir.kalapod.app.domain.report.DayPoint
import ir.kalapod.app.domain.report.ProductStat
import ir.kalapod.app.ui.cart.ProductPickerSheet
import ir.kalapod.app.ui.cart.SummaryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onBack: () -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pickerProducts by vm.pickerProducts.collectAsStateWithLifecycle()
    val pickerQuery by vm.pickerQuery.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val cur = s.currency

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("گزارش‌ها") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> FilterChip(s.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) }) }
            }
            AssistChip(
                onClick = { vm.pickerQuery.value = ""; picking = true },
                label = { Text("کالا: " + (s.product?.name ?: "همه")) },
                trailingIcon = if (s.product != null) ({ IconButton(onClick = { vm.setProduct(null) }) { Icon(Icons.Outlined.Close, "همه کالاها") } }) else null,
            )

            val r = s.report
            if (r == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("فروش خالص", PersianFormat.money(r.netSales, cur), Modifier.weight(1f), emphasized = true)
                Stat("سود ناخالص", PersianFormat.money(r.grossProfit, cur), Modifier.weight(1f))
            }
            if (s.product == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("خرید خالص", PersianFormat.money(r.netPurchases, cur), Modifier.weight(1f))
                    Stat("تعداد فاکتور فروش", PersianFormat.number(r.saleCount.toLong()), Modifier.weight(1f))
                }
            }

            Section("روند فروش") {
                if (r.daily.all { it.netSales == 0L }) {
                    Text("در این بازه فروشی ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SalesChart(r.daily, cur)
                }
            }

            Section(if (s.product == null) "پرفروش‌ترین کالاها" else "فروش این کالا") {
                if (r.best.isEmpty()) Text("فروشی ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.best.forEach { StatRow(it, cur) }
            }
            if (s.product == null && r.worst.isNotEmpty()) {
                Section("کم‌فروش‌ترین کالاها") { r.worst.forEach { StatRow(it, cur) } }
            }
            if (s.product == null) {
                Section("وضعیت فعلی") {
                    SummaryRow("ارزش موجودی انبار (بهای تمام‌شده)", PersianFormat.money(s.stockValue, cur))
                    SummaryRow("طلب از مشتریان", PersianFormat.money(s.receivables, cur))
                    SummaryRow("بدهی به تأمین‌کنندگان", PersianFormat.money(s.payables, cur))
                }
            }
        }
    }

    if (picking) {
        ProductPickerSheet(
            products = pickerProducts, query = pickerQuery, onQuery = { vm.pickerQuery.value = it },
            currency = cur, showPurchasePrice = false,
            onPick = { vm.setProduct(it); picking = false },
            onCreateNew = null,
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier, emphasized: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatRow(p: ProductStat, cur: Currency) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "مقدار: " + PersianFormat.quantity(p.quantityMilli) + "  ·  سود: " + PersianFormat.money(p.profit, cur),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(PersianFormat.money(p.netRevenue, cur), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

/** Simple bar chart, newest day on the left (reading order for RTL users is right → left in time). */
@Composable
private fun SalesChart(days: List<DayPoint>, cur: Currency) {
    val max = days.maxOf { it.netSales }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val negColor = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text("بیشترین: " + PersianFormat.money(max, cur), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val n = days.size
        val slot = size.width / n
        val bw = (slot * 0.65f).coerceAtLeast(2f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.5f)
        days.forEachIndexed { i, d ->
            // RTL: first day at the right edge.
            val x = size.width - (i + 1) * slot + (slot - bw) / 2
            val h = (Math.abs(d.netSales).toFloat() / max) * (size.height - 4f)
            drawRoundRect(
                if (d.netSales >= 0) barColor else negColor,
                topLeft = Offset(x, size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val first = JalaliDate.of(days.first().date)
        val last = JalaliDate.of(days.last().date)
        Text("${PersianFormat.digits(first.day.toString())} ${JalaliDate.MONTH_NAMES[first.month - 1]}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Text("${PersianFormat.digits(last.day.toString())} ${JalaliDate.MONTH_NAMES[last.month - 1]}", style = MaterialTheme.typography.bodySmall)
    }
}
