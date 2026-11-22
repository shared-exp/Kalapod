package ir.kalapod.app.ui.parties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.dao.PartyEntry
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.data.repository.PartyKind
import ir.kalapod.app.data.repository.PartyRepository
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.ui.common.formatDateTime
import ir.kalapod.app.ui.common.label
import ir.kalapod.app.voice.DictationButton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PartyDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: PartyRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val kind = PartyKind.valueOf(saved.get<String>("kind") ?: "CUSTOMER")
    var id: Long = saved.get<Long>("id") ?: 0L
        private set
    val isNew get() = id == 0L

    val currency = settings.settings.map { it.currency }.stateIn(viewModelScope, SharingStarted.Eagerly, Currency.TOMAN)
    val balance = repo.observeBalances(kind).map { l -> l.firstOrNull { it.id == id }?.balance ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val history = repo.observeHistory(kind, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun load(): Pair<String, String?>? = if (isNew) null else repo.get(kind, id)

    suspend fun save(name: String, phone: String): String? =
        repo.save(kind, id, name, phone).fold({ id = it; null }, { it.message })

    suspend fun settle(amountText: String, method: PaymentMethod, note: String, uuid: String): String? {
        val amount = InputParser.money(amountText, currency.value) ?: return "مبلغ نامعتبر است."
        return repo.settle(kind, id, amount, method, note, uuid)
    }

    suspend fun reversePayment(paymentId: Long, reason: String) = repo.reversePayment(paymentId, reason)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyDetailScreen(onBack: () -> Unit, onOpenDocument: (Long) -> Unit, vm: PartyDetailViewModel = hiltViewModel()) {
    val cur by vm.currency.collectAsStateWithLifecycle()
    val balance by vm.balance.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val customer = vm.kind == PartyKind.CUSTOMER
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(vm.isNew) }
    var settleOpen by remember { mutableStateOf(false) }
    var reverseTarget by remember { mutableStateOf<PartyEntry?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.load()?.let { name = it.first; phone = it.second.orEmpty() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) (if (customer) "مشتری جدید" else "تأمین‌کننده جدید") else name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
                actions = { if (!editing) TextButton(onClick = { editing = true }) { Text("ویرایش") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editing) {
                OutlinedTextField(
                    name, { name = it; error = null }, label = { Text("نام *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { DictationButton("party-name") { name = it } },
                )
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("تلفن") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val wasNew = vm.isNew
                        scope.launch { error = vm.save(name, phone); if (error == null) { if (wasNew) onBack() else editing = false } }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("ذخیره") }
            }
            if (!vm.isNew) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("مانده حساب", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        BalanceText(balance, cur, customer)
                    }
                }
                Button(onClick = { settleOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (customer) "دریافت پول از مشتری" else "پرداخت به تأمین‌کننده", style = MaterialTheme.typography.titleMedium)
                }
                Text("گردش حساب", style = MaterialTheme.typography.titleSmall)
                if (history.isEmpty()) Text("هنوز گردشی ندارد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (e.kind == "DOC") onOpenDocument(e.refId) else if (!e.reversed) reverseTarget = e
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entryLabel(e, customer) + if (e.reversed) " (باطل‌شده)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatDateTime(e.occurredAt) + (e.invoiceNumber?.let { "  " + PersianFormat.ltr(it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            PersianFormat.money(e.amount, cur),
                            textDecoration = if (e.reversed) TextDecoration.LineThrough else null,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (settleOpen) {
        var amount by remember { mutableStateOf(if (balance > 0) InputParser.moneyForEdit(balance, cur) else "") }
        var method by remember { mutableStateOf(PaymentMethod.CASH) }
        var note by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        val uuid = remember { UUID.randomUUID().toString() }
        AlertDialog(
            onDismissRequest = { settleOpen = false },
            title = { Text(if (customer) "دریافت از ${name}" else "پرداخت به ${name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        amount, { amount = it; err = null }, label = { Text("مبلغ (${cur.label})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PaymentMethod.CASH, PaymentMethod.CARD).forEach { m ->
                            FilterChip(method == m, onClick = { method = m }, label = { Text(m.label) })
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("توضیح (اختیاری)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { err = vm.settle(amount, method, note, uuid); if (err == null) settleOpen = false } }) { Text("ثبت") }
            },
            dismissButton = { TextButton(onClick = { settleOpen = false }) { Text("انصراف") } },
        )
    }

    reverseTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { reverseTarget = null },
            title = { Text("ابطال این پرداخت؟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(PersianFormat.money(target.amount, cur) + " — " + formatDateTime(target.occurredAt))
                    OutlinedTextField(reason, { reason = it }, label = { Text("دلیل *") })
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { err = vm.reversePayment(target.refId, reason); if (err == null) reverseTarget = null } }) {
                    Text("ابطال", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { reverseTarget = null }) { Text("انصراف") } },
        )
    }
}

private fun entryLabel(e: PartyEntry, customer: Boolean): String = when (e.kind) {
    "DOC" -> runCatching { TransactionType.valueOf(e.label).label }.getOrDefault(e.label)
    else -> {
        val method = e.method?.let { runCatching { PaymentMethod.valueOf(it).label }.getOrNull() }.orEmpty()
        val received = e.label == "IN"
        (if (received) "دریافت" else "پرداخت") + " $method"
    }
}
