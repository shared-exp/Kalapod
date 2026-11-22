package ir.kalapod.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.kalapod.app.data.local.dao.ProductDao
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.domain.voice.CatalogProduct
import ir.kalapod.app.domain.voice.ParsedCommand
import ir.kalapod.app.domain.voice.ResolvedLine
import ir.kalapod.app.domain.voice.VoiceCommandParser
import ir.kalapod.app.domain.voice.VoiceOperation
import ir.kalapod.app.domain.voice.VoiceResolver
import ir.kalapod.app.domain.voice.TypeAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VoicePhase {
    data object Idle : VoicePhase
    /** First use of the online recognizer: ask before any audio leaves the phone. */
    data object AskConsent : VoicePhase
    /** No usable engine: offer the one-time offline download. */
    data class NeedOfflineModel(val progress: Float? = null, val error: String? = null) : VoicePhase
    data class Listening(val engine: String, val partial: String = "") : VoicePhase
    /**
     * Confirm/fix what was heard. While [asking] is set, the mic is listening for the type of that
     * line («کدام رب گوجه فرنگی؟» → «چین چین»); [answerPartial]/[answerNote] show progress/problems.
     */
    data class Review(
        val heard: String,
        val command: ParsedCommand,
        val lines: List<ResolvedLine>,
        val asking: Int? = null,
        val answerPartial: String = "",
        val answerNote: String? = null,
    ) : VoicePhase
    data class Error(val message: String) : VoicePhase
}

/**
 * Drives one voice-entry session: pick an engine, listen, parse, resolve against the catalog, and
 * let the user fix what's uncertain. The host screen applies the confirmed lines to its own cart.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val offline: OfflineModelManager,
    private val products: ProductDao,
    private val aliases: ir.kalapod.app.data.local.dao.AliasDao,
    private val reports: ir.kalapod.app.data.local.dao.ReportDao,
) : ViewModel() {
    private val _phase = MutableStateFlow<VoicePhase>(VoicePhase.Idle)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private var engine: SpeechEngine? = null
    /** The engine this session listened with; reused to hear the answer to «which type?». */
    private var sessionEngine: SpeechEngine? = null

    /** Set while the user is away defining a product heard in this session. */
    var awaitingDefine = false
    private var forPurchase = false

    /** Dictation mode: deliver the heard text as-is (search box, a name, a counted quantity). */
    private var rawMode = false
    private val _rawText = MutableStateFlow<String?>(null)
    /** Last dictated text; the host consumes it with [consumeRawText]. */
    val rawText: StateFlow<String?> = _rawText.asStateFlow()
    fun consumeRawText() { _rawText.value = null }

    fun beginDictation() { rawMode = true; start() }

    val isDictation get() = rawMode

    fun retry(forPurchase: Boolean) = if (rawMode) beginDictation() else begin(forPurchase)

    /** Starts an item session. [forPurchase] chooses which default prices fill unspecified prices. */
    fun begin(forPurchase: Boolean) {
        this.forPurchase = forPurchase
        rawMode = false
        start()
    }

    private fun start() {
        viewModelScope.launch {
            val consent = settings.settings.first().onlineVoiceConsent
            val onlineOk = SystemSpeechEngine.isAvailable(context) && isOnline()
            when {
                onlineOk && consent == true -> listen(SystemSpeechEngine(context))
                offline.isInstalled -> listen(VoskSpeechEngine(offline.model()))
                onlineOk && consent == null -> _phase.value = VoicePhase.AskConsent
                else -> _phase.value = VoicePhase.NeedOfflineModel()
            }
        }
    }

    fun answerConsent(allowed: Boolean) {
        viewModelScope.launch {
            settings.setOnlineVoiceConsent(allowed)
            if (allowed) listen(SystemSpeechEngine(context))
            else if (offline.isInstalled) listen(VoskSpeechEngine(offline.model()))
            else _phase.value = VoicePhase.NeedOfflineModel()
        }
    }

    fun installOfflineModel() {
        viewModelScope.launch {
            _phase.value = VoicePhase.NeedOfflineModel(progress = 0f)
            val r = offline.install { p -> _phase.update { if (it is VoicePhase.NeedOfflineModel) it.copy(progress = p) else it } }
            if (r.isSuccess) listen(VoskSpeechEngine(offline.model()))
            else _phase.value = VoicePhase.NeedOfflineModel(error = "دانلود انجام نشد. اتصال اینترنت را بررسی کنید و دوباره امتحان کنید.")
        }
    }

    private fun listen(e: SpeechEngine) {
        engine?.cancel()
        engine = e
        sessionEngine = e
        _phase.value = VoicePhase.Listening(e.id)
        e.start(object : SpeechCallbacks {
            override fun onPartial(text: String) = _phase.update { if (it is VoicePhase.Listening) it.copy(partial = text) else it }
            override fun onFinal(text: String) = process(text)
            override fun onError(message: String) { _phase.value = VoicePhase.Error(message) }
        })
    }

    /** User tapped "done talking". */
    fun stopListening() = engine?.stop()

    /** Also usable without the microphone (e.g. typed text or tests). */
    fun process(text: String) {
        if (text.isBlank()) { _phase.value = VoicePhase.Error("صدایی شنیده نشد. دوباره بگویید."); return }
        if (rawMode) { engine?.cancel(); engine = null; _rawText.value = text.trim(); _phase.value = VoicePhase.Idle; return }
        engine?.cancel(); engine = null
        viewModelScope.launch {
            val cmd = VoiceCommandParser.parse(text)
            val catalog = products.observeActive().first().map {
                CatalogProduct(it.id, it.name, it.unit, it.salePrice, it.lastPurchasePrice, it.stockMilli)
            }
            val learned = aliases.all().associate { it.alias to it.productId }
            val sold = salesCounts()
            // Options: what sells most first, so the usual choice is one tap (or one word) away.
            val lines = VoiceResolver.resolve(cmd, catalog, forPurchase, learned).map { l ->
                if (l.options.size < 2) l else l.copy(options = l.options.sortedByDescending { sold[it.id] ?: 0L })
            }
            if (lines.isEmpty()) {
                _phase.value = VoicePhase.Error("کالایی در جمله پیدا نشد: «$text»")
            } else {
                _phase.value = VoicePhase.Review(text, cmd, lines)
                askNext()
            }
        }
    }

    fun choose(index: Int, productId: Long) {
        val r = _phase.value as? VoicePhase.Review ?: return
        val line = r.lines[index]
        val p = line.options.firstOrNull { it.id == productId } ?: return
        if (r.asking != null) { engine?.cancel(); engine = null }
        _phase.value = r.copy(
            lines = r.lines.toMutableList().also { it[index] = VoiceResolver.choose(it[index], p, forPurchase) },
            asking = null, answerPartial = "", answerNote = null,
        )
        // Remember misheard words → product (fewer questions next time). Never for a general name:
        // «رب گوجه فرنگی» must always ask which type, with nothing pre-selected.
        val heard = ir.kalapod.app.domain.voice.ProductMatcher.fold(line.heard.name)
        if (heard.isNotBlank() && !line.generic) viewModelScope.launch {
            aliases.put(ir.kalapod.app.data.local.entity.ProductAliasEntity(alias = heard, productId = p.id, createdAt = System.currentTimeMillis()))
        }
        askNext()
    }

    /** Listens for the type of the first line that still needs one (tapping an option always works too). */
    private fun askNext() {
        val r = _phase.value as? VoicePhase.Review ?: return
        val index = r.lines.indexOfFirst { it.needsChoice }
        if (index < 0) return
        val e = sessionEngine ?: return
        engine?.cancel()
        engine = e
        _phase.value = r.copy(asking = index, answerPartial = "", answerNote = null)
        e.start(object : SpeechCallbacks {
            override fun onPartial(text: String) = _phase.update {
                if (it is VoicePhase.Review && it.asking == index) it.copy(answerPartial = text) else it
            }
            override fun onFinal(text: String) = answer(index, text)
            override fun onError(message: String) = _phase.update {
                if (it is VoicePhase.Review && it.asking == index) {
                    it.copy(asking = null, answerPartial = "", answerNote = "جواب شنیده نشد؛ دوباره بگویید یا یکی از گزینه‌ها را لمس کنید.")
                } else it
            }
        })
    }

    /** Ask again by voice (after a missed answer). */
    fun askAgain() = askNext()

    /** Matches a spoken answer («چین چین», «اولی») against the options of line [index]. */
    fun answer(index: Int, text: String) {
        val r = _phase.value as? VoicePhase.Review ?: return
        val line = r.lines.getOrNull(index) ?: return
        engine?.cancel(); engine = null
        val picked = TypeAnswer.pick(text, line.options)
        if (picked != null) {
            choose(index, picked.id)
        } else {
            _phase.value = r.copy(
                asking = null, answerPartial = "",
                answerNote = if (text.isBlank()) "جواب شنیده نشد؛ دوباره بگویید یا لمس کنید."
                else "«${text.trim()}» با هیچ گزینه‌ای جور نشد؛ دوباره بگویید یا لمس کنید.",
            )
        }
    }

    private suspend fun salesCounts(): Map<Long, Long> {
        val zone = java.time.ZoneId.systemDefault()
        val from = java.time.LocalDate.now().minusDays(90).atStartOfDay(zone).toInstant().toEpochMilli()
        return reports.saleLines(from, System.currentTimeMillis(), null)
            .groupBy { it.productId }.mapValues { (_, l) -> l.sumOf { it.quantityMilli } }
    }

    fun drop(index: Int) {
        val r = _phase.value as? VoicePhase.Review ?: return
        val rest = r.lines.filterIndexed { i, _ -> i != index }
        _phase.value = if (rest.isEmpty()) VoicePhase.Idle else r.copy(lines = rest)
    }

    /** After the user defines an unknown product, re-resolve so it gets matched. */
    fun refreshAfterDefine() {
        val r = _phase.value as? VoicePhase.Review ?: return
        process(r.heard)
    }

    fun close() { engine?.cancel(); engine = null; _phase.value = VoicePhase.Idle }

    override fun onCleared() { engine?.cancel() }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        fun operationLabel(op: VoiceOperation?) = when (op) {
            VoiceOperation.SALE -> "فروش"
            VoiceOperation.PURCHASE -> "خرید"
            VoiceOperation.RETURN -> "مرجوعی"
            null -> null
        }
    }
}
