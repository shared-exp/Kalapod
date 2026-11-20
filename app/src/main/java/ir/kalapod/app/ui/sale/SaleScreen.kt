package ir.kalapod.app.ui.sale

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
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.ui.cart.CartLineCard
import ir.kalapod.app.ui.cart.PartyField
import ir.kalapod.app.ui.cart.ProductPickerSheet
import ir.kalapod.app.ui.cart.Settlement
import ir.kalapod.app.ui.cart.SummaryRow

@Composable
fun SaleScreen(onCreateProduct: () -> Unit, vm: SaleViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pickerProducts by vm.pickerProducts.collectAsStateWithLifecycle()
    val pickerQuery by vm.pickerQuery.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val cur = s.currency
    val totals = vm.totals(s)

    LaunchedEffect(s.lastPosted) {
        s.lastPosted?.let {
            snackbar.showSnackbar("فروش ثبت شد — فاکتور ${PersianFormat.digits(it.invoiceNumber)}")
            vm.consumePosted()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Text("ثبت فروش", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 12.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(s.lines, key = { _, l -> l.productId }) { i, line ->
                CartLineCard(
                    line, cur, onChange = { vm.updateLine(i, it) }, onRemove = { vm.removeLine(i) },
                    warning = vm.stockWarning(line, s.lines),
                )
            }
            item {
                FilledTonalButton(
                    onClick = { vm.pickerQuery.value = ""; showPicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Text(if (s.lines.isEmpty()) "افزودن کالا به سبد فروش" else "افزودن کالای دیگر")
                }
            }
            if (s.lines.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        s.discountText, { v -> vm.edit { it.copy(discountText = v) } },
                        label = { Text("تخفیف (${cur.label})") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                item {
                    Text("نحوه دریافت", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Settlement.entries.forEach { opt ->
                            FilterChip(s.settlement == opt, onClick = { vm.edit { it.copy(settlement = opt) } }, label = { Text(opt.label) })
                        }
                    }
                    if (s.settlement == Settlement.CREDIT) {
                        OutlinedTextField(
                            s.receivedNowText, { v -> vm.edit { it.copy(receivedNowText = v) } },
                            label = { Text("مبلغ دریافت‌شده الان (${cur.label})") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                item {
                    PartyField(
                        if (s.settlement == Settlement.CREDIT) "مشتری *" else "مشتری",
                        s.customerText, { v -> vm.edit { it.copy(customerText = v) } },
                        customers, s.customerId, { id -> vm.edit { it.copy(customerId = id) } },
                    )
                }
            }
        }

        if (s.lines.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("مبلغ قابل پرداخت", totals?.let { PersianFormat.money(it.total, cur) } ?: "—", strong = true)
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { vm.check()?.let(vm::showError) ?: run { confirm = true } },
                        enabled = !s.posting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("ثبت فروش", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        SnackbarHost(snackbar)
    }

    if (showPicker) {
        ProductPickerSheet(
            products = pickerProducts, query = pickerQuery, onQuery = { vm.pickerQuery.value = it },
            currency = cur, showPurchasePrice = false,
            onPick = { vm.add(it); showPicker = false },
            onCreateNew = { showPicker = false; onCreateProduct() },
            onDismiss = { showPicker = false },
        )
    }

    if (confirm && totals != null) {
        val got = vm.receivedNow(s, totals)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تأیید فروش") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.lines.forEach { l ->
                        SummaryRow(
                            "${l.name} × ${PersianFormat.quantity(l.quantityMilli() ?: 0)}",
                            l.lineTotal(cur)?.let { PersianFormat.money(it, cur) } ?: "",
                        )
                    }
                    if (totals.discount > 0) SummaryRow("تخفیف", "−" + PersianFormat.money(totals.discount, cur))
                    SummaryRow("جمع کل", PersianFormat.money(totals.total, cur), strong = true)
                    SummaryRow("دریافت‌شده (${s.settlement.label})", PersianFormat.money(got, cur))
                    if (totals.total - got > 0) SummaryRow("نسیه مشتری", PersianFormat.money(totals.total - got, cur))
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.post() }) { Text("ثبت نهایی") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("اصلاح") } },
        )
    }

    if (s.shortages.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissShortages,
            title = { Text("موجودی کافی نیست") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.shortages.forEach {
                        Text("${it.name}: درخواست ${PersianFormat.quantity(it.requestedMilli)}، موجودی ${PersianFormat.quantity(it.availableMilli)}")
                    }
                    Text("اگر کالا واقعاً در فروشگاه هست، می‌توانید فروش را ثبت کنید؛ موجودی منفی می‌شود تا بعداً اصلاح شود.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.post(allowOversell = true) }) { Text("با این حال ثبت کن") } },
            dismissButton = { TextButton(onClick = vm::dismissShortages) { Text("انصراف") } },
        )
    }
}
