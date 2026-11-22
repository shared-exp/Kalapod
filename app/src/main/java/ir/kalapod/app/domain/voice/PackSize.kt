package ir.kalapod.app.domain.voice

/**
 * Pack sizes that are part of a product's identity: «۲۵۰ گرمی», «دویست و پنجاه گرمی», «نیم کیلویی»,
 * «یک و نیم لیتری», «۵۰۰ سی‌سی», «250g». All of them become one canonical token — grams ("250g") or
 * millilitres ("1500ml") — so the written product name and the spoken words compare exactly.
 *
 * Only the adjectival/packaging forms count as a size («۵۰۰ گرمی»). The bare unit («۵۰۰ گرم») is a
 * quantity, which the command parser handles separately.
 */
object PackSize {
    /** Words that, after a number, describe the size of one pack → (canonical unit, factor). */
    private val sizeWords: Map<String, Pair<String, Long>> = mapOf(
        "گرمی" to ("g" to 1L), "گ" to ("g" to 1L),
        "کیلویی" to ("g" to 1_000L), "کیلوئی" to ("g" to 1_000L), "کیلوگرمی" to ("g" to 1_000L),
        "لیتری" to ("ml" to 1_000L), "سیسی" to ("ml" to 1L),
        "میلی" to ("ml" to 1L), "میلیلیتری" to ("ml" to 1L), "میلیلیتر" to ("ml" to 1L),
        "g" to ("g" to 1L), "gr" to ("g" to 1L), "kg" to ("g" to 1_000L),
        "ml" to ("ml" to 1L), "cc" to ("ml" to 1L), "l" to ("ml" to 1_000L), "lit" to ("ml" to 1_000L),
    )
    private val glued = Regex("^(\\d+(?:\\.\\d+)?)(گرمی|گرم|کیلویی|لیتری|لیتر|سیسی|g|gr|kg|ml|cc|l)$")
    private val canonical = Regex("^(\\d+)(g|ml)$")

    fun isSizeWord(w: String) = w in sizeWords

    /**
     * Replaces every "number + size word" (or glued "250گرم", "250g") in [tokens] with a canonical
     * token like "250g". Other tokens are returned unchanged.
     */
    fun normalize(input: List<String>): List<String> {
        val tokens = mergeCc(input)
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            glued.matchEntire(t)?.let { m ->
                val unit = when (m.groupValues[2]) {
                    "گرمی", "گرم", "g", "gr" -> "g" to 1L
                    "کیلویی", "kg" -> "g" to 1_000L
                    "لیتری", "لیتر", "l" -> "ml" to 1_000L
                    else -> "ml" to 1L
                }
                val milli = (m.groupValues[1].toDouble() * 1000).toLong()
                out += token(milli, unit); i++; return@let
            } ?: run {
                val num = SpokenNumbers.parse(tokens, i)
                val unit = num?.let { tokens.getOrNull(it.next) }?.let { sizeWords[it] }
                if (num != null && unit != null) {
                    out += token(num.milli, unit); i = num.next + 1
                } else {
                    out += t; i++
                }
            }
        }
        return out
    }

    /** «سی سی» (heard as two words) → «سیسی», so «سی» isn't read as the number 30. */
    fun mergeCc(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == "سی" && tokens.getOrNull(i + 1) == "سی") { out += "سیسی"; i += 2 } else { out += tokens[i]; i++ }
        }
        return out
    }

    /** Canonical size tokens in an already-normalized name, e.g. {"250g"}. */
    fun sizesIn(tokens: List<String>): Set<String> = tokens.filter { canonical.matches(it) }.toSet()

    /** "250g" → 250, "1500ml" → 1500; null if not a size token. */
    fun amount(token: String): Long? = canonical.matchEntire(token)?.groupValues?.get(1)?.toLong()

    private fun token(milli: Long, unit: Pair<String, Long>): String = "${milli * unit.second / 1000}${unit.first}"
}
