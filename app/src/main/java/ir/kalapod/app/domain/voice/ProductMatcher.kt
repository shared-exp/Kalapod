package ir.kalapod.app.domain.voice

/**
 * Finds the catalog product a spoken name refers to, tolerating speech-recognition noise:
 *  - sound-alike letters (ش/چ/ج/ژ, س/ص/ث, ز/ذ/ض/ظ, ت/ط, ح/ه, ک/گ/ق/غ, ا/آ/ع/ء) — «شین شین» → «چین چین»
 *  - dropped/extra letters (Levenshtein) — «ارنگی» → «فرنگی»
 *  - partial names — «روغن» → «روغن لادن» when it's the only oil
 *  - filler words that match nothing in the catalog — «لطفاً»، «همون»
 *  - names the user already taught it (aliases learned from earlier choices)
 * It only commits when one product is clearly best; otherwise it asks with the closest options.
 */
object ProductMatcher {

    data class Candidate(val id: Long, val name: String)
    data class Scored(val candidate: Candidate, val score: Double)

    sealed interface Match {
        /** Safe to use without asking. */
        data class Sure(val candidate: Candidate) : Match
        /**
         * Several plausible products: ask the user to pick (best first).
         * [generic] = the user named a general product («رب گوجه فرنگی») and several types of it are in
         * stock; the type must be asked every time — no learned shortcut, no default.
         */
        data class Ambiguous(val options: List<Candidate>, val generic: Boolean = false) : Match
        /** Nothing close: offer to define a new product. */
        data object None : Match
    }

    private const val SURE = 0.80
    private const val MIN = 0.50
    private const val GAP = 0.08
    /** A spoken word this unlike every catalog word is treated as filler, not as part of the name. */
    private const val FILLER = 0.55

    /**
     * @param aliases learned spoken names → product id, keyed by [fold]; an exact hit wins outright.
     */
    fun match(spoken: String, catalog: List<Candidate>, aliases: Map<String, Long> = emptyMap()): Match {
        val key = fold(spoken)
        // A general name that fits several types is always a question, even if it was answered before.
        val generic = genericMatches(spoken, catalog)
        if (generic.size >= 2) return Match.Ambiguous(generic, generic = true)
        aliases[key]?.let { id -> catalog.firstOrNull { it.id == id }?.let { return Match.Sure(it) } }
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

    /** Generic threshold: every spoken word must be found in the product name at least this well. */
    private const val CONTAINS = 0.85

    /**
     * Products whose names contain every meaningful word the user said — e.g. «رب گوجه فرنگی» fits
     * both «رب گوجه فرنگی چین چین» and «رب گوجه فرنگی تبرک». Best match first.
     */
    fun genericMatches(spoken: String, catalog: List<Candidate>): List<Candidate> {
        val words = meaningfulWords(spoken, catalog)
        if (words.isEmpty()) return emptyList()
        return catalog.map { it to fold(it.name).split(' ').filter(String::isNotEmpty) }
            .filter { (_, p) -> p.isNotEmpty() && words.all { w -> p.maxOf { similarity(w, it) } >= CONTAINS } }
            .map { (c, _) -> Scored(c, score(words.joinToString(" "), fold(c.name))) }
            .sortedByDescending { it.score }
            .map { it.candidate }
    }

    private fun meaningfulWords(spoken: String, catalog: List<Candidate>): List<String> {
        val words = fold(spoken).split(' ').filter { it.isNotEmpty() }
        val vocabulary = catalog.flatMap { fold(it.name).split(' ') }.filter { it.isNotEmpty() }.toSet()
        return words.filter { w -> (vocabulary.maxOfOrNull { similarity(w, it) } ?: 0.0) >= FILLER }.ifEmpty { words }
    }

    fun rank(spoken: String, catalog: List<Candidate>): List<Scored> {
        val s = fold(spoken)
        if (s.isBlank()) return emptyList()
        val folded = catalog.map { it to fold(it.name) }
        val vocabulary = folded.flatMap { (_, n) -> n.split(' ') }.filter { it.isNotEmpty() }.toSet()
        // Drop words that look like nothing we sell; keep words that match *some* product (they discriminate).
        val words = s.split(' ').filter { it.isNotEmpty() }
        val meaningful = words.filter { w -> vocabulary.maxOfOrNull { similarity(w, it) } ?: 0.0 >= FILLER }
            .ifEmpty { words }
        val query = meaningful.joinToString(" ")
        return folded.map { (c, n) -> Scored(c, score(query, n)) }.sortedByDescending { it.score }
    }

    /** 0..1 similarity between two folded names. */
    internal fun score(spoken: String, product: String): Double {
        if (spoken == product) return 1.0
        val sTokens = spoken.split(' ').filter { it.isNotEmpty() }
        val pTokens = product.split(' ').filter { it.isNotEmpty() }
        if (sTokens.isEmpty() || pTokens.isEmpty()) return 0.0
        // Every spoken word should be found in the product name…
        val coverage = sTokens.map { st -> pTokens.maxOf { similarity(st, it) } }.average()
        // …and, lightly, the product's words should have been said (so the more specific name loses when unsaid).
        val reverse = pTokens.map { pt -> sTokens.maxOf { similarity(pt, it) } }.average()
        val whole = similarity(spoken.replace(" ", ""), product.replace(" ", ""))
        return maxOf(whole, 0.8 * coverage + 0.2 * reverse)
    }

    /** Similarity of two words: plain spelling, or by sound with a small discount. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val plain = levenshteinSimilarity(a, b)
        val bySound = levenshteinSimilarity(sound(a), sound(b)) * 0.97
        return maxOf(plain, bySound)
    }

    internal fun levenshteinSimilarity(a: String, b: String): Double {
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

    /** Collapses letters that sound alike (or that recognizers swap) into one representative. */
    internal fun sound(word: String): String = buildString(word.length) {
        for (c in word) append(
            when (c) {
                'چ', 'ج', 'ژ' -> 'ش'
                'ص', 'ث' -> 'س'
                'ذ', 'ض', 'ظ' -> 'ز'
                'ط' -> 'ت'
                'ح' -> 'ه'
                'گ', 'ق', 'غ' -> 'ک'
                'آ', 'ع', 'ء', 'أ', 'إ' -> 'ا'
                'ئ' -> 'ی'
                else -> c
            },
        )
    }

    /** Normal form for comparing names (also the key for learned aliases). */
    fun fold(name: String): String = PersianText.normalize(name)
        .replace(",", " ")
        .replace('آ', 'ا')
        .replace("ئ", "ی")
        .replace(Regex("\\d+(\\.\\d+)?"), " ")              // sizes like "1.5" are rarely spoken the same way
        .split(' ')
        .filter { it.isNotEmpty() && it !in noise }
        .joinToString(" ") { colloquial[it] ?: it.removeSuffix("ها").ifEmpty { it } }

    private val noise = setOf("لیتری", "گرمی", "کیلویی", "عددی", "بسته", "بزرگ", "کوچک", "متوسط", "تا", "عدد", "دونه")
    private val colloquial = mapOf(
        "نوشابع" to "نوشابه", "ماکارانی" to "ماکارونی", "ماکارنی" to "ماکارونی",
        "روغان" to "روغن", "تخممرغ" to "تخم‌مرغ", "چایی" to "چای", "ماس" to "ماست",
        "فرنگ" to "فرنگی", "گوجهفرنگی" to "گوجه فرنگی",
    )
}
