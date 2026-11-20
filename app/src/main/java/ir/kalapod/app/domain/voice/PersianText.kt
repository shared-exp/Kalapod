package ir.kalapod.app.domain.voice

/** Text normalization shared by the command parser and the product matcher. */
object PersianText {
    /** Unifies Arabic/Persian letter variants, digits and spacing; strips punctuation (keeps '.' between digits). */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    'ة' -> 'ه'
                    'أ', 'إ' -> 'ا'
                    'ؤ' -> 'و'
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    '٫' -> '.'
                    '‌' -> null // "دونه‌ای" → "دونهای"
                    '‏', '‎' -> ' '
                    'ً', 'ٌ', 'ٍ', 'َ', 'ُ', 'ِ', 'ّ', 'ْ' -> null // diacritics
                    ',', '،', '؛', ';', '!', '?', '؟', ':', '«', '»', '"', '(', ')' -> SEP
                    else -> c
                } ?: continue,
            )
        }
        // A '.' not between digits is sentence punctuation.
        val s = Regex("(?<!\\d)\\.|\\.(?!\\d)").replace(sb, " ").replace(SEP.toString(), " , ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Clause separator token produced by [normalize] for commas and similar punctuation. */
    const val COMMA = ","
    private const val SEP = '\u0001'

    fun tokens(input: String): List<String> = normalize(input).split(' ').filter { it.isNotEmpty() }

    /** Loose form for matching names: also drops spaces and common plural/suffix noise. */
    fun key(input: String): String = normalize(input).replace(",", "").replace(" ", "").replace('آ', 'ا')
}
