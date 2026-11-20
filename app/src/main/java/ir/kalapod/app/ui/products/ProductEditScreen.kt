package ir.kalapod.app.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.ui.common.label

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductEditScreen(onDone: () -> Unit, vm: ProductEditViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showNewCategory by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }

    LaunchedEffect(f.done) { if (f.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (f.isNew) "کالای جدید" else "ویرایش کالا") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (!f.loaded) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                f.name, { v -> vm.edit { it.copy(name = v) } },
                label = { Text("نام کالا *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Section("واحد")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductUnit.entries.forEach { u ->
                    FilterChip(f.unit == u, onClick = { vm.edit { it.copy(unit = u) } }, label = { Text(u.label) })
                }
            }

            Section("دسته‌بندی")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(f.categoryId == null, onClick = { vm.edit { it.copy(categoryId = null) } }, label = { Text("بدون دسته") })
                categories.forEach { c ->
                    FilterChip(f.categoryId == c.id, onClick = { vm.edit { it.copy(categoryId = c.id) } }, label = { Text(c.name) })
                }
                FilterChip(false, onClick = { showNewCategory = true }, label = { Text("دسته جدید") },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) })
            }

            val cur = f.currency.label
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(f.salePrice, { v -> vm.edit { it.copy(salePrice = v) } }, "قیمت فروش ($cur) *", Modifier.weight(1f))
                NumberField(f.purchasePrice, { v -> vm.edit { it.copy(purchasePrice = v) } }, "قیمت خرید ($cur)", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(f.minStock, { v -> vm.edit { it.copy(minStock = v) } }, "حداقل موجودی (${f.unit.label})", Modifier.weight(1f), decimal = true)
                NumberField(f.leadTimeDays, { v -> vm.edit { it.copy(leadTimeDays = v) } }, "زمان تأمین (روز)", Modifier.weight(1f))
            }
            if (f.isNew) {
                NumberField(
                    f.openingStock, { v -> vm.edit { it.copy(openingStock = v) } },
                    "موجودی فعلی در قفسه (${f.unit.label})", Modifier.fillMaxWidth(), decimal = true,
                )
            }

            Section("بارکدها")
            if (f.barcodes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.barcodes.forEach { code ->
                        InputChip(
                            selected = false, onClick = { vm.removeBarcode(code) }, label = { Text(code) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "حذف بارکد") },
                        )
                    }
                }
            }
            OutlinedTextField(
                f.newBarcode, { v -> vm.edit { it.copy(newBarcode = v.filter { c -> !c.isWhitespace() }) } },
                label = { Text("افزودن بارکد (دستی)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                trailingIcon = { IconButton(onClick = vm::addBarcode) { Icon(Icons.Outlined.Add, "افزودن") } },
            )

            f.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(
                onClick = vm::save, enabled = !f.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.large,
            ) { Text("ذخیره", style = MaterialTheme.typography.titleMedium) }

            if (!f.isNew) {
                OutlinedButton(onClick = { confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("بایگانی کالا")
                }
            }
        }
    }

    if (showNewCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCategory = false },
            title = { Text("دسته‌بندی جدید") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("نام دسته") }) },
            confirmButton = { TextButton(onClick = { vm.addCategory(name); showNewCategory = false }) { Text("افزودن") } },
            dismissButton = { TextButton(onClick = { showNewCategory = false }) { Text("انصراف") } },
        )
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("بایگانی کالا") },
            text = { Text("کالا از فهرست‌ها پنهان می‌شود اما سوابق خرید و فروش آن حفظ می‌شود.") },
            confirmButton = { TextButton(onClick = { vm.setArchived(true); confirmArchive = false }) { Text("بایگانی") } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}
