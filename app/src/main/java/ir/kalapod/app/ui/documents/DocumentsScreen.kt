package ir.kalapod.app.ui.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.dao.DocumentRow
import ir.kalapod.app.data.local.dao.TransactionDao
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.ui.common.formatDateTime
import ir.kalapod.app.ui.common.label
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DocumentsState(
    val type: TransactionType? = null,
    val rows: List<DocumentRow>? = null,
    val currency: Currency = Currency.TOMAN,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    dao: TransactionDao, settings: SettingsRepository, savedState: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    /** Picking the invoice a return belongs to: only live sales and purchases, searchable. */
    val forReturn: Boolean = savedState.get<Boolean>("forReturn") ?: false
    val type = MutableStateFlow<TransactionType?>(null)
    val query = MutableStateFlow("")
    val state: StateFlow<DocumentsState> = combine(
        type, type.flatMapLatest { dao.observeDocuments(it, 500) }, settings.settings, query,
    ) { t, rows, s, q ->
        val shown = rows.filter { r ->
            (!forReturn || (r.tx.status == ir.kalapod.app.data.local.entity.TransactionStatus.POSTED &&
                r.tx.type in setOf(TransactionType.SALE, TransactionType.PURCHASE))) &&
                (q.isBlank() || listOfNotNull(r.invoiceNumber, r.customerName, r.supplierName).any { it.contains(q.trim()) })
        }
        DocumentsState(t, shown, s.currency)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsState())
}

@Composable
fun DocumentsScreen(onOpen: (Long) -> Unit, vm: DocumentsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            if (vm.forReturn) "مرجوعی: فاکتور را انتخاب کنید" else "فاکتورها و اسناد",
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 12.dp),
        )
        androidx.compose.material3.OutlinedTextField(
            q, { vm.query.value = it }, singleLine = true, shape = MaterialTheme.shapes.large,
            placeholder = { Text("شماره فاکتور یا نام مشتری/تأمین‌کننده") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(s.type == null, onClick = { vm.type.value = null }, label = { Text("همه") }) }
            items(if (vm.forReturn) listOf(TransactionType.SALE, TransactionType.PURCHASE) else TransactionType.entries) { t ->
                FilterChip(s.type == t, onClick = { vm.type.value = t }, label = { Text(t.label) })
            }
        }
        val rows = s.rows
        if (rows != null && rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("هنوز سندی ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows.orEmpty(), key = { it.tx.id }) { r -> DocumentCard(r, s.currency) { onOpen(r.tx.id) } }
            }
        }
    }
}

@Composable
private fun DocumentCard(r: DocumentRow, cur: Currency, onClick: () -> Unit) {
    val reversed = r.tx.status == TransactionStatus.REVERSED
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.tx.type.label + (if (reversed) " (باطل‌شده)" else ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (reversed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    listOfNotNull(r.customerName ?: r.supplierName, "${PersianFormat.number(r.itemCount.toLong())} قلم").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    formatDateTime(r.tx.occurredAt) + "  " + PersianFormat.ltr(r.invoiceNumber.orEmpty()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                PersianFormat.money(r.tx.total, cur),
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (reversed) TextDecoration.LineThrough else null,
            )
        }
    }
}
