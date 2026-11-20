package ir.kalapod.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.domain.voice.ParsedCommand
import ir.kalapod.app.domain.voice.ResolvedLine
import ir.kalapod.app.domain.voice.VoiceOperation
import ir.kalapod.app.ui.common.label

/** Round mic button used next to "add item" everywhere items can be entered. */
@Composable
fun VoiceButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick, modifier = modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Icon(Icons.Outlined.Mic, "ثبت با صدا", Modifier.size(28.dp)) }
}

/**
 * Voice entry sheet. Shown while [VoiceViewModel.phase] isn't Idle.
 * @param expected the operation of the hosting screen, to warn if the user said something else.
 * @param onApply confirmed lines (all with a product) plus the full command (party, payment).
 * @param onDefine define a product the catalog doesn't have yet (name pre-filled).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceSheet(
    vm: VoiceViewModel,
    expected: VoiceOperation?,
    currency: Currency,
    onApply: (List<ResolvedLine>, ParsedCommand) -> Unit,
    onDefine: (String) -> Unit,
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    if (phase == VoicePhase.Idle) return
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.close()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ModalBottomSheet(onDismissRequest = vm::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val p = phase) {
                VoicePhase.AskConsent -> {
                    Text("تشخیص گفتار آنلاین", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "برای دقت بیشتر، صدای شما برای تبدیل به متن به سرویس تشخیص گفتار گوشی (معمولاً گوگل) فرستاده می‌شود. " +
                            "اطلاعات فروشگاه فرستاده نمی‌شود. اگر نخواهید، می‌توانید از حالت آفلاین استفاده کنید.",
                    )
                    Button(onClick = { vm.answerConsent(true) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("موافقم") }
                    OutlinedButton(onClick = { vm.answerConsent(false) }, Modifier.fillMaxWidth()) { Text("فقط آفلاین") }
                }

                is VoicePhase.NeedOfflineModel -> {
                    Text("بسته تشخیص صدای آفلاین", style = MaterialTheme.typography.titleLarge)
                    Text("برای ثبت با صدا بدون اینترنت، یک بار بسته فارسی (حدود ۶۲ مگابایت) دانلود می‌شود.")
                    p.progress?.let {
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        Text(PersianFormat.digits("${(it * 100).toInt()}٪"))
                    }
                    p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (p.progress == null) {
                        Button(onClick = vm::installOfflineModel, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("دانلود") }
                        OutlinedButton(onClick = vm::close, Modifier.fillMaxWidth()) { Text("انصراف") }
                    }
                }

                is VoicePhase.Listening -> {
                    Text(if (p.engine == "offline") "در حال گوش دادن (آفلاین)…" else "در حال گوش دادن…", style = MaterialTheme.typography.titleLarge)
                    Text(
                        p.partial.ifBlank { "مثلاً: «سه تا روغن و دو بسته ماکارونی فروختم»" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p.partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
                        1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FilledIconButton(onClick = vm::stopListening, modifier = Modifier.size(88.dp).scale(pulse), shape = CircleShape) {
                            Icon(Icons.Outlined.Mic, "پایان صحبت", Modifier.size(40.dp))
                        }
                    }
                    Text("بعد از گفتن، روی دکمه بزنید", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                }

                is VoicePhase.Review -> ReviewContent(p, vm, expected, currency, onApply, onDefine)

                is VoicePhase.Error -> {
                    Text(p.message, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.begin(expected == VoiceOperation.PURCHASE) }, Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
                    OutlinedButton(onClick = vm::close, Modifier.fillMaxWidth()) { Text("بستن") }
                }

                VoicePhase.Idle -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewContent(
    p: VoicePhase.Review,
    vm: VoiceViewModel,
    expected: VoiceOperation?,
    currency: Currency,
    onApply: (List<ResolvedLine>, ParsedCommand) -> Unit,
    onDefine: (String) -> Unit,
) {
    Text("«${p.heard}»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val said = VoiceViewModel.operationLabel(p.command.operation)
    if (expected != null && p.command.operation != null && p.command.operation != expected) {
        Text("گفتید «$said» ولی این صفحه «${VoiceViewModel.operationLabel(expected)}» است. اقلام به همین صفحه اضافه می‌شوند.",
            color = MaterialTheme.colorScheme.error)
    }
    p.command.partyName?.let { Text("طرف حساب: $it") }

    p.lines.forEachIndexed { i, line ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.product?.name ?: line.heard.name,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.drop(i) }) { Icon(Icons.Outlined.Close, "حذف") }
                }
                val unit = line.product?.unit?.label ?: line.heard.unit?.label.orEmpty()
                Text(
                    "${PersianFormat.quantity(line.quantityMilli)} $unit" +
                        (line.unitPrice?.let { " × " + PersianFormat.money(it, currency) } ?: ""),
                )
                if (line.needsChoice) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.options.forEach { o -> FilterChip(false, onClick = { vm.choose(i, o.id) }, label = { Text(o.name) }) }
                    }
                }
                if (line.isUnknown) {
                    TextButton(onClick = { onDefine(line.heard.name) }) { Text("تعریف «${line.heard.name}» به‌عنوان کالای جدید") }
                }
                line.notes.filterNot { line.needsChoice && it.startsWith("کدام") }.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
    val ready = p.lines.filter { it.product != null }
    Button(
        onClick = { onApply(ready, p.command); vm.close() },
        enabled = ready.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            if (ready.size == p.lines.size) "افزودن به سبد"
            else "افزودن ${PersianFormat.number(ready.size.toLong())} قلم مشخص‌شده",
            style = MaterialTheme.typography.titleMedium,
        )
    }
    OutlinedButton(onClick = { vm.begin(expected == VoiceOperation.PURCHASE) }, Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
}
