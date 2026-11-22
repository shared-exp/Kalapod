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

    /** Number of leading words shared by all names («رب گوجه فرنگی …» → 3). */
    fun commonPrefixWords(names: List<String>): Int {
        val split = names.map { it.split(' ').filter(String::isNotEmpty) }
        if (split.size < 2) return 0
        var n = 0
        while (split.all { it.size > n } && split.all { it[n] == split[0][n] }) n++
        return n
    }
}
