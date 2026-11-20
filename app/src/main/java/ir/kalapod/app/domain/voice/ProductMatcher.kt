package ir.kalapod.app.domain.voice

/**
 * Matches a spoken product name against the catalog, tolerating speech-recognition noise,
 * colloquial spelling and partial names ("روغن" → "روغن لادن ۱.۵ لیتری" when it's the only oil).
 */
object ProductMatcher {

    data class Candidate(val id: Long, val name: String)
    data class Scored(val candidate: Candidate, val score: Double)

    sealed interface Match {
        /** Safe to use without asking. */
        data class Sure(val candidate: Candidate) : Match
        /** Several plausible products: ask the user to pick (best first). */
        data class Ambiguous(val options: List<Candidate>) : Match
        /** Nothing close: offer to define a new product. */
        data object None : Match
    }

    private const val SURE = 0.82
    private const val MIN = 0.5
    private const val GAP = 0.12

    fun match(spoken: String, catalog: List<Candidate>): Match {
        val ranked = rank(spoken, catalog)
        val top = ranked.firstOrNull() ?: return Match.None
        if (top.score < MIN) return Match.None
        val second = ranked.getOrNull(1)?.score ?: 0.0
        return if (top.score >= SURE && top.score - second >= GAP) {
            Match.Sure(top.candidate)
        } else {
            Match.Ambiguous(ranked.filter { it.score >= MIN }.take(4).map { it.candidate })
        }
    }

    fun rank(spoken: String, catalog: List<Candidate>): List<Scored> {
        val s = fold(spoken)
        if (s.isBlank()) return emptyList()
        return catalog.map { Scored(it, score(s, fold(it.name))) }.sortedByDescending { it.score }
    }

    /** 0..1 similarity between two folded names. */
    internal fun score(spoken: String, product: String): Double {
        if (spoken == product) return 1.0
        val sTokens = spoken.split(' ').filter { it.isNotEmpty() }
        val pTokens = product.split(' ').filter { it.isNotEmpty() }
        // How well each spoken word is covered by some product word (handles extra words in the product name).
        val coverage = sTokens.map { st -> pTokens.maxOfOrNull { similarity(st, it) } ?: 0.0 }.average()
        // Penalize product names that have many words the user didn't say, but only lightly:
        // "روغن" should still find "روغن لادن".
        val reverse = pTokens.map { pt -> sTokens.maxOfOrNull { similarity(pt, it) } ?: 0.0 }.average()
        val whole = similarity(spoken.replace(" ", ""), product.replace(" ", ""))
        return maxOf(whole, 0.8 * coverage + 0.2 * reverse)
    }

    /** Normalized Levenshtein similarity. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** Folds spelling variants that speech recognizers and people mix up. */
    internal fun fold(name: String): String = PersianText.normalize(name)
        .replace(",", " ")
        .replace('آ', 'ا')
        .replace("ئ", "ی")
        .replace(Regex("\\d+(\\.\\d+)?"), " ")              // sizes like "1.5" are rarely spoken the same way
        .split(' ')
        .filter { it.isNotEmpty() && it !in noise }
        .joinToString(" ") { colloquial[it] ?: it.removeSuffix("ها").ifEmpty { it } }

    private val noise = setOf("لیتری", "گرمی", "کیلویی", "عددی", "بسته", "بزرگ", "کوچک", "متوسط", "تا", "عدد", "دونه")
    private val colloquial = mapOf(
        "نوشابه" to "نوشابه", "نوشابع" to "نوشابه", "ماکارانی" to "ماکارونی", "ماکارنی" to "ماکارونی",
        "پنیر" to "پنیر", "رب" to "رب", "روغان" to "روغن", "شیر" to "شیر", "تخم مرغ" to "تخم‌مرغ", "تخممرغ" to "تخم‌مرغ",
        "چایی" to "چای", "قند" to "قند", "شکر" to "شکر", "برنج" to "برنج", "اب" to "اب", "ماس" to "ماست",
    )
}
