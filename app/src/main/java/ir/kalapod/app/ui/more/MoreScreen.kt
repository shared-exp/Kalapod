package ir.kalapod.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One hub entry. Only built features are listed — no placeholders (SPEC §16). */
data class MoreEntry(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun MoreScreen(
    onReports: () -> Unit,
    onDocuments: () -> Unit,
    onParties: () -> Unit,
    onSettings: () -> Unit,
    extra: List<MoreEntry> = emptyList(),
) {
    val entries = listOf(
        MoreEntry("گزارش‌ها", "فروش، سود، پرفروش‌ها و نمودار", Icons.Outlined.BarChart, onReports),
        MoreEntry("فاکتورها و اسناد", "همه خریدها، فروش‌ها و مرجوعی‌ها", Icons.Outlined.ReceiptLong, onDocuments),
        MoreEntry("مشتریان و تأمین‌کنندگان", "حساب‌ها، نسیه‌ها، دریافت و پرداخت", Icons.Outlined.People, onParties),
    ) + extra + MoreEntry("تنظیمات", "نام فروشگاه، واحد پول، ظاهر", Icons.Outlined.Settings, onSettings)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("بیشتر", style = MaterialTheme.typography.headlineSmall)
        entries.forEach { e ->
            Card(
                Modifier.fillMaxWidth().clickable(onClick = e.onClick),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(e.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium)
                        Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
