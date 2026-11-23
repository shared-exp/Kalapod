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
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
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
                        p.partial.ifBlank { when {
                            vm.isDictation -> "بگویید…"
                            expected == VoiceOperation.RETURN -> "مثلاً: «یک بسته ماکارونی و دو تا روغن»"
                            expected == VoiceOperation.PURCHASE -> "مثلاً: «ده کیلو برنج کیلویی ۱۸۰ هزار خریدم»"
                            else -> "مثلاً: «سه تا روغن و دو بسته ماکارونی فروختم»"
                        } },
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
                    if (ir.kalapod.app.BuildConfig.DEBUG) {
                        // QA only (emulators have no real microphone): type what would have been said.
                        var typed by androidx.compose.runtime.saveable.rememberSaveable {
                            androidx.compose.runtime.mutableStateOf("یه دونه رب گوجه فرنگی و دو تا روغن لادن فروختم")
                        }
                        androidx.compose.material3.OutlinedTextField(
                            typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("آزمایشی: جمله را بنویسید") },
                            trailingIcon = { TextButton(onClick = { vm.process(typed) }) { Text("ارسال") } },
                        )
                        listOf("یه رب چین چین فروختم", "۱۰ تا رب ۷۰۰ چین چین ۲۰ تا روغن لادن ۱.۵ فروختم", "ده تا رب گوجه ارنگی شین شین ۵۰۰ خریدم", "پنج کارتن نوشابه زمزم خریدم")
                            .forEach { sample -> TextButton(onClick = { vm.process(sample) }) { Text(sample, style = MaterialTheme.typography.bodySmall) } }
                    }
                    Text("بعد از گفتن، روی دکمه بزنید", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                }

                is VoicePhase.Review -> ReviewContent(p, vm, expected, currency, onApply, onDefine)

                is VoicePhase.Error -> {
                    Text(p.message, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.retry(expected == VoiceOperation.PURCHASE) }, Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
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
    val returnOnWrongScreen = p.command.operation == VoiceOperation.RETURN && expected != VoiceOperation.RETURN
    if (returnOnWrongScreen) {
        Text(
            "برای مرجوعی، از تب «فاکتورها» فاکتور مربوط را باز کنید و «ثبت مرجوعی» را بزنید؛ آنجا هم می‌توانید با صدا بگویید.",
            color = MaterialTheme.colorScheme.error,
        )
    } else if (expected != null && p.command.operation != null && p.command.operation != expected) {
        Text("گفتید «$said» ولی این صفحه «${VoiceViewModel.operationLabel(expected)}» است. اقلام به همین صفحه اضافه می‌شوند.",
            color = MaterialTheme.colorScheme.error)
    }
    p.command.partyName?.let { Text("طرف حساب: $it") }

    p.lines.forEachIndexed { i, line ->
        val pending = line.needsChoice
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (pending) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.product?.name ?: line.heard.name,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.drop(i) }) { Icon(Icons.Outlined.Close, "حذف") }
                }
                val unit = (line.product?.unit ?: line.heard.unit
                    ?: line.options.map { it.unit }.distinct().singleOrNull())?.label.orEmpty()
                Text(
                    "${PersianFormat.quantity(line.quantityMilli)} $unit" +
                        (line.unitPrice?.let { " × " + PersianFormat.money(it, currency) } ?: ""),
                )
                if (pending) {
                    // The question, by voice and on screen; nothing is pre-selected.
                    Text(
                        "کدام «${line.heard.name}»؟",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.options.forEach { o ->
                            val out = o.stockMilli <= 0
                            FilterChip(
                                selected = false,
                                onClick = { vm.choose(i, o.id) },
                                modifier = Modifier.alpha(if (out) 0.55f else 1f),
                                label = {
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Text(optionLabel(o, line.options), style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            (if (out) "ناموجود" else "موجودی ${PersianFormat.quantity(o.stockMilli)}") +
                                                " · " + PersianFormat.money(o.salePrice, currency),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                            )
                        }
                        if (line.allowNew) {
                            FilterChip(
                                selected = false,
                                onClick = { onDefine(line.heard.name) },
                                label = { Text("«${line.heard.name}» کالای جدید است", style = MaterialTheme.typography.titleSmall) },
                                leadingIcon = { Icon(Icons.Outlined.Add, null) },
                            )
                        }
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
    // Nothing is added until every line is a definite product: no partial carts, nothing assumed.
    val needType = p.lines.count { it.needsChoice }
    val unknown = p.lines.count { it.isUnknown }
    Button(
        onClick = { onApply(p.lines, p.command); vm.close() },
        enabled = needType == 0 && unknown == 0 && p.lines.isNotEmpty() && !returnOnWrongScreen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            when {
                needType > 0 -> "نوع ${PersianFormat.number(needType.toLong())} قلم را انتخاب کنید"
                unknown > 0 -> "کالای ناشناخته را تعریف یا حذف کنید"
                expected == VoiceOperation.RETURN -> "ثبت در فرم مرجوعی"
                else -> "افزودن به سبد"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
    OutlinedButton(onClick = { vm.begin(expected == VoiceOperation.PURCHASE) }, Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
}

/**
 * Small mic icon for any text field: dictates and hands back the text.
 * [key] keeps each field's voice session separate on the same screen.
 */
@Composable
fun DictationButton(key: String, onText: (String) -> Unit) {
    val vm: VoiceViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "dictation-$key")
    val text by vm.rawText.collectAsStateWithLifecycle()
    LaunchedEffect(text) { text?.let { onText(it); vm.consumeRawText() } }
    IconButton(onClick = vm::beginDictation) { Icon(Icons.Outlined.Mic, "گفتن به‌جای تایپ") }
    VoiceSheet(vm, expected = null, currency = Currency.TOMAN, onApply = { _, _ -> }, onDefine = {})
}

/** First spoken number in [text] as milli-units ("دوازده و نیم کیلو" → 12500), or null. */
fun spokenQuantity(text: String): Long? {
    val t = ir.kalapod.app.domain.voice.PersianText.tokens(text)
    for (i in t.indices) ir.kalapod.app.domain.voice.SpokenNumbers.parse(t, i)?.let { return it.milli }
    return null
}

/** Shows what makes an option different («چین چین») instead of repeating the shared «رب گوجه فرنگی». */
private fun optionLabel(o: ir.kalapod.app.domain.voice.CatalogProduct, all: List<ir.kalapod.app.domain.voice.CatalogProduct>): String {
    val words = o.name.trim().split(' ').filter { it.isNotEmpty() }
    val common = ir.kalapod.app.domain.voice.TypeAnswer.commonPrefixWords(
        all.map { a -> a.name.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") },
    )
    return words.drop(common).joinToString(" ").ifBlank { o.name }
}
