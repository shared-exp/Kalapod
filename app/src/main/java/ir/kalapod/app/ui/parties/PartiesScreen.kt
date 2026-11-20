package ir.kalapod.app.ui.parties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.dao.PartyBalance
import ir.kalapod.app.data.repository.PartyKind
import ir.kalapod.app.data.repository.PartyRepository
import ir.kalapod.app.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PartiesState(
    val kind: PartyKind = PartyKind.CUSTOMER,
    val onlyOpen: Boolean = false,
    val rows: List<PartyBalance>? = null,
    val currency: Currency = Currency.TOMAN,
) {
    val totalOpen get() = rows.orEmpty().filter { it.balance > 0 }.sumOf { it.balance }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PartiesViewModel @Inject constructor(repo: PartyRepository, settings: SettingsRepository) : ViewModel() {
    val kind = MutableStateFlow(PartyKind.CUSTOMER)
    val onlyOpen = MutableStateFlow(false)
    val state: StateFlow<PartiesState> = combine(
        kind, onlyOpen, kind.flatMapLatest { repo.observeBalances(it) }, settings.settings,
    ) { k, open, rows, s -> PartiesState(k, open, if (open) rows.filter { it.balance != 0L } else rows, s.currency) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartiesState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartiesScreen(
    onBack: () -> Unit,
    onOpen: (PartyKind, Long) -> Unit,
    onAdd: (PartyKind) -> Unit,
    vm: PartiesViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val customers = s.kind == PartyKind.CUSTOMER
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("طرف حساب‌ها") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(s.kind) }, icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(if (customers) "مشتری جدید" else "تأمین‌کننده جدید") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = if (customers) 0 else 1) {
                Tab(customers, onClick = { vm.kind.value = PartyKind.CUSTOMER }, text = { Text("مشتریان") })
                Tab(!customers, onClick = { vm.kind.value = PartyKind.SUPPLIER }, text = { Text("تأمین‌کنندگان") })
            }
            Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(s.onlyOpen, onClick = { vm.onlyOpen.value = !s.onlyOpen }, label = { Text(if (customers) "فقط بدهکارها" else "فقط طلبکارها") })
                Text(
                    (if (customers) "جمع طلب شما: " else "جمع بدهی شما: ") + PersianFormat.money(s.totalOpen, s.currency),
                    Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            val rows = s.rows
            if (rows != null && rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (customers) "مشتری‌ای ثبت نشده است." else "تأمین‌کننده‌ای ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows.orEmpty(), key = { it.id }) { r ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpen(s.kind, r.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    r.phone?.let { Text(PersianFormat.digits(it), style = MaterialTheme.typography.bodySmall) }
                                }
                                BalanceText(r.balance, s.currency, customers)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Balance with a plain-language label: who owes whom. */
@Composable
fun BalanceText(balance: Long, currency: Currency, customer: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            PersianFormat.money(Math.abs(balance), currency),
            style = MaterialTheme.typography.titleMedium,
            color = when {
                balance > 0 -> MaterialTheme.colorScheme.error
                balance < 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            when {
                balance == 0L -> "تسویه"
                customer && balance > 0 -> "بدهکار به شما"
                customer -> "طلبکار از شما"
                balance > 0 -> "شما بدهکارید"
                else -> "شما طلبکارید"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
