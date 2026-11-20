package ir.kalapod.app.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.core.jalali.JalaliDate

@Composable
fun DashboardScreen(
    onNewSale: () -> Unit,
    onNewPurchase: () -> Unit,
    onOpenInventory: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnimatedContent(state is DashboardUiState.Ready, label = "dashboard") { ready ->
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            DashboardContent(state as DashboardUiState.Ready, onNewSale, onNewPurchase, onOpenInventory)
        }
    }
}

@Composable
private fun DashboardContent(
    s: DashboardUiState.Ready,
    onNewSale: () -> Unit,
    onNewPurchase: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    val cur = s.settings.currency
    val today = s.data.today
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                s.settings.shopName.ifBlank { "کالاپاد" },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                PersianFormat.longDate(JalaliDate.of(s.date)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Primary actions: the two things a shopkeeper does all day.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onNewSale,
                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Outlined.PointOfSale, null, Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Text("ثبت فروش", style = MaterialTheme.typography.titleMedium)
            }
            FilledTonalButton(
                onClick = onNewPurchase,
                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Outlined.AddShoppingCart, null, Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Text("ثبت خرید", style = MaterialTheme.typography.titleMedium)
            }
        }

        StatCard(
            title = "فروش امروز",
            value = PersianFormat.money(today.netSales, cur),
            caption = "${PersianFormat.number(today.saleCount.toLong())} فاکتور",
            emphasized = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("خرید امروز", PersianFormat.money(today.netPurchases, cur), null, Modifier.weight(1f))
            StatCard(
                "سود تخمینی",
                PersianFormat.money(today.grossProfit, cur),
                null,
                Modifier.weight(1f),
                valueColor = if (today.grossProfit < 0) MaterialTheme.colorScheme.error else null,
            )
        }
        StatCard("ارزش موجودی انبار", PersianFormat.money(s.data.stockValue, cur), "بر پایه بهای تمام‌شده")

        AlertsCard(s, cur, onOpenInventory)
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    caption: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    emphasized: Boolean = false,
    valueColor: Color? = null,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AlertsCard(s: DashboardUiState.Ready, cur: Currency, onOpenInventory: () -> Unit) {
    val low = s.data.lowStock
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon: ImageVector, tint) = if (low.isEmpty()) {
                    Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                } else {
                    Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
                }
                Icon(icon, null, tint = tint)
                Spacer(Modifier.size(8.dp))
                Text("هشدارها", style = MaterialTheme.typography.titleMedium)
            }
            if (low.isEmpty()) {
                Text("کالای رو به اتمام ندارید.", style = MaterialTheme.typography.bodyMedium)
            } else {
                low.take(5).forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "موجودی: ${PersianFormat.quantity(p.stockMilli)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (p.stockMilli <= 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onOpenInventory, colors = ButtonDefaults.textButtonColors()) {
                    Text(
                        if (low.size > 5) "مشاهده همه (${PersianFormat.number(low.size.toLong())})"
                        else "رفتن به انبار",
                    )
                }
            }
        }
    }
}
