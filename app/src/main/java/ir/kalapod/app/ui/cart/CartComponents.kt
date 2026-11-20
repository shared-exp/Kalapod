package ir.kalapod.app.ui.cart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.ui.common.allowsFraction
import ir.kalapod.app.ui.common.label

@Composable
fun CartLineCard(
    line: CartLine,
    currency: Currency,
    onChange: (CartLine) -> Unit,
    onRemove: () -> Unit,
    warning: String? = null,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "حذف از سبد") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    line.quantityText, { onChange(line.copy(quantityText = it)) },
                    label = { Text("مقدار (${line.unit.label})") }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = line.quantityMilli() == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (line.unit.allowsFraction) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                )
                OutlinedTextField(
                    line.priceText, { onChange(line.copy(priceText = it)) },
                    label = { Text("قیمت واحد (${currency.label})") }, singleLine = true, modifier = Modifier.weight(1.4f),
                    isError = line.unitPrice(currency) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row {
                Text(
                    "جمع: " + (line.lineTotal(currency)?.let { PersianFormat.money(it, currency) } ?: "—"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                if (warning != null) Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}

/** Searchable product list in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductPickerSheet(
    products: List<ProductEntity>,
    query: String,
    onQuery: (String) -> Unit,
    currency: Currency,
    showPurchasePrice: Boolean,
    onPick: (ProductEntity) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query, onQuery, placeholder = { Text("نام کالا یا بارکد") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            )
            OutlinedButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Icon(Icons.Outlined.Add, null)
                Text("تعریف کالای جدید")
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(products, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "موجودی: ${PersianFormat.quantity(p.stockMilli)} ${p.unit.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            PersianFormat.money(if (showPurchasePrice) p.lastPurchasePrice else p.salePrice, currency),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Name field with suggestions from existing parties; typing a new name means "create". */
@Composable
fun PartyField(
    label: String,
    text: String,
    onText: (String) -> Unit,
    suggestions: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            text, { onText(it); onSelect(null); focused = true },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = {
                when {
                    selectedId != null -> Text("انتخاب شد")
                    text.isNotBlank() -> Text("«${text.trim()}» به‌عنوان طرف حساب جدید ثبت می‌شود")
                    else -> Text("اختیاری")
                }
            },
        )
        val matches = if (selectedId == null && focused && text.isNotBlank()) {
            suggestions.filter { it.second.contains(text.trim()) }.take(4)
        } else emptyList()
        matches.forEach { (id, name) ->
            Text(
                name,
                Modifier.fillMaxWidth().clickable { onText(name); onSelect(id); focused = false }.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The three ways to add items: speak, pick from the list, or scan barcodes. */
@Composable
fun AddItemsRow(label: String, onPick: () -> Unit, onScan: () -> Unit, onVoice: () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ir.kalapod.app.voice.VoiceButton(onClick = onVoice)
        androidx.compose.material3.FilledTonalButton(
            onClick = onPick, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Outlined.Add, null)
            Text(label)
        }
        androidx.compose.material3.FilledTonalButton(onClick = onScan, modifier = Modifier.heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null)
            Text("اسکن")
        }
    }
}

@Composable
fun UnknownBarcodeDialog(code: String, onDefine: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("کالای ناشناخته") },
        text = { Text("بارکد ${PersianFormat.digits(code)} هنوز برای هیچ کالایی ثبت نشده است. همین حالا تعریفش کنید؟") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDefine) { Text("تعریف کالا") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("بعداً") } },
    )
}
