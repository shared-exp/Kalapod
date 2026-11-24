package ir.kalapod.app.domain.voice

/**
 * Turns spoken Persian numbers into values. Works on tokens already passed through [PersianText.normalize].
 * Values are returned in milli-units (×1000) so "یک و نیم" = 1500 and "نیم" = 500 stay exact.
 *
 * Handles: digits ("180", "2.5"), words ("صد و هشتاد"), scales ("۱۸۰ هزار", "یک میلیون و دویست"),
 * halves ("دو و نیم", "نیم"), colloquial forms ("یه", "شیش", "پونصد").
 */
object SpokenNumbers {
    private val units = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "یکی" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "چار" to 4, "پنج" to 5,
        "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12,
        "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16,
        "هفده" to 17, "هیفده" to 17, "هجده" to 18, "هیجده" to 18, "نوزده" to 19,
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50, "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90,
        "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400, "پانصد" to 500, "پونصد" to 500,
        "ششصد" to 600, "شیشصد" to 600, "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900,
    )
    private val scales = mapOf("هزار" to 1_000L, "میلیون" to 1_000_000L, "ملیون" to 1_000_000L, "میلیارد" to 1_000_000_000L)
    private const val HALF = "نیم"
    private const val AND = "و"

    fun isNumberWord(t: String) = t in units || t in scales || t == HALF || t.toNumberOrNull() != null

    data class Result(val milli: Long, val next: Int)

    /**
     * @param boundary words that, right after a following number, mean that number starts a new
     * phrase ("۳۰ هزار و یک کیلو…": "یک کیلو" is not part of the price).
     */
    fun parse(tokens: List<String>, start: Int, boundary: Set<String> = emptySet()): Result? {
        if (start >= tokens.size || !isNumberWord(tokens[start])) return null
        // "نه" alone is usually "no"; accept it only if followed by another number word or a unit-ish token.
        var total = 0L        // milli
        var current = 0L      // milli, the part below the last scale
        var lastScale = 1L
        var i = start
        var consumedAny = false
        while (i < tokens.size) {
            val t = tokens[i]
            val digits = t.toNumberOrNull()
            when {
                // Two numbers written in digits never form one number («۷۰۰ ۲۰ تا …» is 700, then 20).
                digits != null && consumedAny -> break
                digits != null -> { current += digits; consumedAny = true; i++ }
                t == HALF -> { current += 500; consumedAny = true; i++ }
                t == "صد" && current in 1..9_000 -> { current *= 100; i++ }
                // Number words join only with «و» or after a scale («دو هزار پونصد»); «هفتصد بیست تا …» is
                // 700 and then 20 (the next item's quantity).
                // «یه» right after a scale is the next item's article («۹۰ هزار یه روغن»), not «و یک».
                t in units && consumedAny && tokens[i - 1] != AND && (tokens[i - 1] !in scales || t == "یه") -> break
                t in units -> { current += units.getValue(t) * 1000L; consumedAny = true; i++ }
                t in scales -> {
                    val s = scales.getValue(t)
                    total += (if (current == 0L) 1000L else current) * s
                    current = 0; lastScale = s; consumedAny = true; i++
                }
                t == AND && consumedAny && i + 1 < tokens.size && isNumberWord(tokens[i + 1]) &&
                    tokens.getOrNull(i + 2) !in boundary -> i++
                else -> break
            }
        }
        if (!consumedAny) return null
        // Spoken prices drop "هزار" after millions: "یک میلیون و دویست" = 1,200,000.
        if (lastScale >= 1_000_000L && current in 1..999_000) current *= 1_000
        return Result(total + current, i)
    }

    /** "180" → 180000, "2.5" → 2500 (milli); null if not a plain number. Max three decimals. */
    private fun String.toNumberOrNull(): Long? {
        if (!matches(Regex("\\d{1,15}(\\.\\d{1,3})?"))) return null
        val parts = split('.')
        return parts[0].toLong() * 1000 + (parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0)
    }
}
