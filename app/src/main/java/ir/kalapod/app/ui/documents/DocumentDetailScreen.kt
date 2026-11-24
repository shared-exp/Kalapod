package ir.kalapod.app.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.ui.cart.SummaryRow
import ir.kalapod.app.ui.common.formatDateTime
import ir.kalapod.app.ui.common.label
import kotlinx.coroutines.launch
import ir.kalapod.app.data.pdf.InvoiceActions
import ir.kalapod.app.domain.voice.VoiceOperation
import ir.kalapod.app.voice.VoiceButton
import ir.kalapod.app.voice.VoiceSheet
import ir.kalapod.app.voice.VoiceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: DocumentDetailViewModel = hiltViewModel(),
    voiceVm: VoiceViewModel = hiltViewModel(key = "return-voice"),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showReturn by remember { mutableStateOf(false) }
    var showReverse by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.detail?.let { it.tx.type.label + " " + PersianFormat.ltr(it.invoiceNumber.orEmpty()) } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val d = s.detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val cur = s.currency
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (d.tx.status == TransactionStatus.REVERSED) {
                Text(
                    "این سند باطل شده است" + (d.reversal?.note?.let { " — دلیل: $it" } ?: ""),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall,
                )
            }
            InfoCard {
                SummaryRow("تاریخ", formatDateTime(d.tx.occurredAt))
                d.partyName?.let {
                    SummaryRow(if (d.tx.type == TransactionType.PURCHASE || d.tx.type == TransactionType.PURCHASE_RETURN) "تأمین‌کننده" else "مشتری", it)
                }
                d.tx.note?.takeIf { d.tx.status != TransactionStatus.REVERSED }?.let { SummaryRow("توضیح", it) }
            }
            InfoCard {
                d.items.forEach { r ->
                    Column {
                        Text(r.productName, style = MaterialTheme.typography.titleSmall)
                        SummaryRow(
                            r.item.caseSizeMilli?.takeIf { r.item.quantityMilli > 0 }?.let { size ->
                                PersianFormat.withCases(r.item.quantityMilli, r.unit.label, size) + " × " +
                                    PersianFormat.money(ir.kalapod.app.domain.finance.Money.divRoundHalfUp(r.item.lineTotal * size, r.item.quantityMilli), cur) +
                                    " هر کارتن"
                            } ?: "${PersianFormat.quantity(r.item.quantityMilli)} ${r.unit.label} × ${PersianFormat.money(r.item.unitPrice, cur)}",
                            PersianFormat.money(r.item.lineTotal, cur),
                        )
                    }
                    HorizontalDivider()
                }
                SummaryRow("جمع اقلام", PersianFormat.money(d.tx.subtotal, cur))
                if (d.tx.discount > 0) SummaryRow("تخفیف", "−" + PersianFormat.money(d.tx.discount, cur))
                if (d.tx.extraCost > 0) SummaryRow("هزینه جانبی", PersianFormat.money(d.tx.extraCost, cur))
                SummaryRow("مبلغ کل", PersianFormat.money(d.tx.total, cur), strong = true)
            }
            if (d.payments.isNotEmpty()) {
                InfoCard {
                    Text("پرداخت‌ها", style = MaterialTheme.typography.titleSmall)
                    d.payments.forEach { p ->
                        SummaryRow(
                            (if (p.direction == PaymentDirection.IN) "دریافت " else "پرداخت ") + p.method.label,
                            PersianFormat.money(p.amount, cur),
                        )
                    }
                }
            }
            d.original?.let { o ->
                OutlinedButton(onClick = { onOpen(o.id) }, modifier = Modifier.fillMaxWidth()) { Text("مشاهده فاکتور اصلی") }
            }
            if (d.returns.isNotEmpty()) {
                InfoCard {
                    Text("مرجوعی‌ها", style = MaterialTheme.typography.titleSmall)
                    d.returns.forEach { r ->
                        TextButton(onClick = { onOpen(r.id) }) {
                            Text(
                                formatDateTime(r.occurredAt) + " — " + PersianFormat.money(r.total, cur) +
                                    if (r.status == TransactionStatus.REVERSED) " (باطل‌شده)" else "",
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { scope.launch { runCatching { vm.pdf() }.onSuccess { InvoiceActions.share(context, it, "فاکتور") } } },
                    modifier = Modifier.weight(1f),
                ) { Text("ارسال PDF") }
                FilledTonalButton(
                    onClick = { scope.launch { runCatching { vm.pdf() }.onSuccess { InvoiceActions.print(context, it, it.nameWithoutExtension) } } },
                    modifier = Modifier.weight(1f),
                ) { Text("چاپ") }
            }
            if (d.canReturn) {
                FilledTonalButton(onClick = { vm.startReturn(); showReturn = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("ثبت مرجوعی")
                }
            }
            if (d.canReverse) {
                OutlinedButton(onClick = { showReverse = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("ابطال سند", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showReturn) {
        val d = s.detail!!
        val isSale = d.tx.type == TransactionType.SALE
        AlertDialog(
            onDismissRequest = { showReturn = false },
            title = { Text(if (isSale) "مرجوعی فروش" else "مرجوعی خرید") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceButton(onClick = { voiceVm.begin(forPurchase = !isSale) })
                        Text("بگویید چه چیزی برگشت، مثلاً «یک بسته ماکارونی»", style = MaterialTheme.typography.bodySmall)
                    }
                    s.returnLines.forEachIndexed { i, l ->
                        OutlinedTextField(
                            l.quantityText, { vm.setReturnQty(i, it) }, singleLine = true,
                            enabled = l.remainingMilli > 0,
                            label = { Text("${l.item.productName} (تا ${PersianFormat.quantity(l.remainingMilli)})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    Text("ارزش تقریبی: " + PersianFormat.money(vm.estimatedReturnValue(s), s.currency))
                    OutlinedTextField(
                        s.refundText, vm::setRefund, singleLine = true,
                        label = { Text((if (isSale) "مبلغ پس‌داده‌شده" else "مبلغ پس‌گرفته‌شده") + " (${s.currency.label})") },
                        supportingText = { Text("باقی‌مانده از حساب طرف مقابل کم می‌شود") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PaymentMethod.CASH, PaymentMethod.CARD).forEach { m ->
                            FilterChip(s.refundMethod == m, onClick = { vm.setRefundMethod(m) }, label = { Text(m.label) })
                        }
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { if (vm.submitReturn()) showReturn = false } }) { Text("ثبت مرجوعی") } },
            dismissButton = { TextButton(onClick = { showReturn = false }) { Text("انصراف") } },
        )
    }

    VoiceSheet(
        voiceVm, VoiceOperation.RETURN, s.currency,
        onApply = { lines, _ -> vm.applyVoiceToReturn(lines) },
        onDefine = {},
    )

    if (showReverse) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReverse = false },
            title = { Text("ابطال سند") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اثر این سند روی موجودی و پرداخت‌ها برگردانده می‌شود. خود سند با علامت «باطل‌شده» در سوابق می‌ماند.")
                    OutlinedTextField(reason, { reason = it }, label = { Text("دلیل ابطال *") })
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { if (vm.submitReverse(reason)) showReverse = false } }) {
                    Text("ابطال", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showReverse = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}
