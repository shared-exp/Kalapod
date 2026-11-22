package ir.kalapod.app.domain.voice

/**
 * Understands the spoken answer to «which type?»: a brand/type name («چین چین»), the full name, or
 * an ordinal («اولی», «دومی»). Matching is limited to the offered options, so a short answer is
 * enough and can't drift to an unrelated product. Returns null when nothing fits clearly.
 */
object TypeAnswer {
    private val ordinals = listOf(
        setOf("اولی", "اول", "یکی"), setOf("دومی", "دوم"), setOf("سومی", "سوم"),
        setOf("چهارمی", "چهارم"), setOf("پنجمی", "پنجم"),
    )

    fun pick(answer: String, options: List<CatalogProduct>): CatalogProduct? {
        if (answer.isBlank() || options.isEmpty()) return null
        val words = PersianText.tokens(answer)
        ordinals.forEachIndexed { i, forms ->
            if (words.any { it in forms } && i in options.indices) return options[i]
        }
        bySize(words, options)?.let { return it }
        // Compare against what makes each option different (its type), then the full name.
        val common = commonPrefixWords(options.map { ProductMatcher.fold(it.name) })
        val distinct = options.map { o ->
            ProductMatcher.Candidate(o.id, ProductMatcher.fold(o.name).split(' ').drop(common).joinToString(" ").ifBlank { o.name })
        }
        val m = ProductMatcher.match(answer, distinct) as? ProductMatcher.Match.Sure
            ?: ProductMatcher.match(answer, options.map { ProductMatcher.Candidate(it.id, it.name) }) as? ProductMatcher.Match.Sure
            ?: return null
        return options.first { it.id == m.candidate.id }
    }

    private val smallWords = setOf("کوچیکه", "کوچیک", "کوچک", "کوچکه", "کوچیکتر", "کوچکتر")
    private val bigWords = setOf("بزرگه", "بزرگ", "بزرگتر", "گنده", "گندهه")

    /**
     * Answers about pack size: «۲۵۰ گرمی», «پونصدی», «پونصد», «۵۰۰», «کوچیکه», «بزرگه».
     * Only used when the options differ by size; a bare number must match exactly one option.
     */
    private fun bySize(words: List<String>, options: List<CatalogProduct>): CatalogProduct? {
        val sizes = options.map { o ->
            PackSize.sizesIn(ProductMatcher.fold(o.name).split(' ')).firstNotNullOfOrNull { PackSize.amount(it) }
        }
        if (sizes.any { it == null } || sizes.toSet().size != options.size) return null
        if (words.any { it in smallWords }) return options[sizes.indices.minBy { sizes[it]!! }]
        if (words.any { it in bigWords }) return options[sizes.indices.maxBy { sizes[it]!! }]
        // «پونصدی» → «پونصد»: the colloquial "the 500 one"
        val cleaned = words.map { w -> if (w.endsWith("ی") && SpokenNumbers.isNumberWord(w.dropLast(1))) w.dropLast(1) else w }
        val normalized = PackSize.normalize(cleaned)
        PackSize.sizesIn(normalized).firstNotNullOfOrNull { PackSize.amount(it) }?.let { said ->
            val hit = sizes.indexOf(said)
            if (hit >= 0) return options[hit]
        }
        for (i in normalized.indices) {
            val n = SpokenNumbers.parse(normalized, i) ?: continue
            if (n.milli % 1000 != 0L) continue
            val value = n.milli / 1000
            val hits = sizes.indices.filter { sizes[it] == value || sizes[it] == value * 1000 }
            if (hits.size == 1) return options[hits.single()]
        }
        return null
    }

    /** Number of leading words shared by all names («رب گوجه فرنگی …» → 3). */
    fun commonPrefixWords(names: List<String>): Int {
        val split = names.map { it.split(' ').filter(String::isNotEmpty) }
        if (split.size < 2) return 0
        var n = 0
        while (split.all { it.size > n } && split.all { it[n] == split[0][n] }) n++
        return n
    }
}
