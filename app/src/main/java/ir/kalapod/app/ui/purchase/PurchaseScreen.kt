package ir.kalapod.app.ui.purchase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.ui.cart.AddItemsRow
import ir.kalapod.app.ui.cart.CartLineCard
import ir.kalapod.app.ui.cart.UnknownBarcodeDialog
import ir.kalapod.app.ui.scanner.BarcodeScannerDialog
import ir.kalapod.app.domain.voice.VoiceOperation
import ir.kalapod.app.voice.VoiceSheet
import ir.kalapod.app.voice.VoiceViewModel
import ir.kalapod.app.ui.cart.PartyField
import ir.kalapod.app.ui.cart.ProductPickerSheet
import ir.kalapod.app.ui.cart.Settlement
import ir.kalapod.app.ui.cart.SummaryRow

@Composable
fun PurchaseScreen(
    onBack: () -> Unit,
    onCreateProduct: (barcode: String?) -> Unit,
    onDefineNamed: (name: String, quantityMilli: Long) -> Unit,
    onOpenDocument: (Long) -> Unit = {},
    vm: PurchaseViewModel = hiltViewModel(),
    voiceVm: VoiceViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pickerProducts by vm.pickerProducts.collectAsStateWithLifecycle()
    val pickerQuery by vm.pickerQuery.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val scanFeedback by vm.scanFeedback.collectAsStateWithLifecycle()
    val unknownBarcode by vm.unknownBarcode.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val cur = s.currency
    val totals = vm.totals(s)
    val recentPurchases by vm.recentPurchases.collectAsStateWithLifecycle()

    LaunchedEffect(s.lastPosted) {
        s.lastPosted?.let {
            snackbar.showSnackbar("خرید ثبت شد — فاکتور ${PersianFormat.ltr(it.invoiceNumber)}")
            vm.consumePosted()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت")
            }
            Text("ورود کالا (خرید)", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(s.lines, key = { _, l -> "${l.productId}-${l.caseSizeMilli}" }) { i, line ->
                CartLineCard(line, cur, onChange = { vm.updateLine(i, it) }, onRemove = { vm.removeLine(i) }, showSalePrice = true)
            }
            item {
                AddItemsRow(
                    if (s.lines.isEmpty()) "افزودن کالا" else "کالای دیگر",
                    onPick = { vm.pickerQuery.value = ""; showPicker = true },
                    onScan = { vm.scanFeedback.value = ""; showScanner = true },
                    onVoice = { voiceVm.begin(forPurchase = true) },
                )
            }
            // Purchase history: every purchase, newest first; tap to open (return, cancel, PDF).
            val recent = recentPurchases
            if (s.lines.isEmpty() && recent.isNotEmpty()) {
                item { Text("خریدهای اخیر", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(recent.size, key = { "buy-" + recent[it].tx.id }) { k ->
                    ir.kalapod.app.ui.cart.DocumentHistoryRow(recent[k], cur, onOpen = { onOpenDocument(recent[k].tx.id) })
                }
            }
            if (s.lines.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoneyField(s.discountText, { v -> vm.edit { it.copy(discountText = v) } }, "تخفیف (${cur.label})", Modifier.weight(1f))
                        MoneyField(s.extraText, { v -> vm.edit { it.copy(extraText = v) } }, "هزینه جانبی (${cur.label})", Modifier.weight(1f))
                    }
                }
                item {
                    PartyField(
                        "تأمین‌کننده", s.supplierText, { v -> vm.edit { it.copy(supplierText = v) } },
                        suppliers, s.supplierId, { id -> vm.edit { it.copy(supplierId = id) } },
                    )
                }
                item {
                    Text("نحوه پرداخت", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Settlement.entries.forEach { opt ->
                            FilterChip(
                                s.settlement == opt, onClick = { vm.edit { it.copy(settlement = opt) } },
                                label = { Text(when (opt) { Settlement.CASH -> "نقد"; Settlement.CARD -> "کارت"; Settlement.CREDIT -> "نسیه" }) },
                            )
                        }
                    }
                    if (s.settlement == Settlement.CREDIT) {
                        MoneyField(
                            s.paidNowText, { v -> vm.edit { it.copy(paidNowText = v) } },
                            "مبلغ پرداخت‌شده الان (${cur.label})", Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (s.lines.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("جمع کل", totals?.let { PersianFormat.money(it.total, cur) } ?: "—", strong = true)
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { vm.check()?.let(vm::showError) ?: run { confirm = true } },
                        enabled = !s.posting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("ثبت خرید", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        SnackbarHost(snackbar)
    }

    LaunchedEffect(Unit) { vm.consumePending() }
    // Returning from "define product" during a voice session: re-match what was heard.
    LaunchedEffect(Unit) { if (voiceVm.awaitingDefine) { voiceVm.awaitingDefine = false; voiceVm.refreshAfterDefine() } }
    VoiceSheet(
        voiceVm, VoiceOperation.PURCHASE, cur,
        onApply = vm::applyVoice,
        onDefine = { name -> voiceVm.awaitingDefine = true; onDefineNamed(name, voiceVm.definingPieces(name)) },
    )

    if (showScanner) {
        BarcodeScannerDialog(feedback = scanFeedback, onCode = vm::onBarcode, onClose = { showScanner = false })
    }
    unknownBarcode?.let { code ->
        UnknownBarcodeDialog(
            code,
            onDefine = { vm.unknownBarcode.value = null; showScanner = false; onCreateProduct(code) },
            onDismiss = { vm.unknownBarcode.value = null },
        )
    }

    if (showPicker) {
        ProductPickerSheet(
            products = pickerProducts, query = pickerQuery, onQuery = { vm.pickerQuery.value = it },
            currency = cur, showPurchasePrice = true,
            onPick = { vm.add(it); showPicker = false },
            onCreateNew = { showPicker = false; onCreateProduct(null) },
            onDismiss = { showPicker = false },
        )
    }

    if (confirm && totals != null) {
        val paidNow = if (s.settlement == Settlement.CREDIT) InputParser.money(s.paidNowText.ifBlank { "0" }, cur) ?: 0 else totals.total
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تأیید خرید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.lines.forEach { l ->
                        SummaryRow(
                            "${l.name} × ${PersianFormat.quantity(l.quantityMilli() ?: 0)}",
                            l.lineTotal(cur)?.let { PersianFormat.money(it, cur) } ?: "",
                        )
                    }
                    if (totals.discount > 0) SummaryRow("تخفیف", "−" + PersianFormat.money(totals.discount, cur))
                    if (totals.extraCost > 0) SummaryRow("هزینه جانبی", PersianFormat.money(totals.extraCost, cur))
                    SummaryRow("جمع کل", PersianFormat.money(totals.total, cur), strong = true)
                    SummaryRow("پرداخت‌شده", PersianFormat.money(paidNow, cur))
                    if (totals.total - paidNow > 0) {
                        SummaryRow("بدهی به تأمین‌کننده", PersianFormat.money(totals.total - paidNow, cur))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.post() }) { Text("ثبت نهایی") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("اصلاح") } },
        )
    }
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
