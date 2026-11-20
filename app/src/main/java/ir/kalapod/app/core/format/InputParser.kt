package ir.kalapod.app.core.format

import ir.kalapod.app.domain.finance.Money

/** Parses what the user typed (Persian or Latin digits, with separators) into integer domain values. */
object InputParser {
    private val groupChars = setOf(',', '٬', '،', ' ', '‌', '_')

    private fun normalize(text: String): String =
        PersianFormat.toAsciiDigits(text.trim()).filterNot { it in groupChars }.replace('٫', '.').replace('/', '.')

    /** Whole amount in the display currency → rial. Returns null for invalid/negative input. */
    fun money(text: String, currency: Currency): Long? {
        val s = normalize(text)
        if (s.isEmpty() || !s.all { it.isDigit() } || s.length > 15) return null
        val v = s.toLong()
        return if (currency == Currency.TOMAN) Money.tomanToRial(v) else v
    }

    /** Decimal quantity ("2.5", "۲٫۵") → milli-units. Max three decimals. Null if invalid or negative. */
    fun quantity(text: String): Long? {
        val s = normalize(text)
        if (s.isEmpty() || s.count { it == '.' } > 1 || !s.all { it.isDigit() || it == '.' }) return null
        val parts = s.split('.')
        val whole = parts[0].ifEmpty { "0" }
        val frac = parts.getOrElse(1) { "" }
        if (frac.length > 3 || whole.length > 12) return null
        return whole.toLong() * Money.MILLI + frac.padEnd(3, '0').toLong()
    }

    /** Rial → plain digits for pre-filling an edit field in the display currency. */
    fun moneyForEdit(rial: Long, currency: Currency): String =
        (if (currency == Currency.TOMAN) Money.rialToToman(rial) else rial).toString()

    fun quantityForEdit(milli: Long): String {
        val whole = milli / Money.MILLI
        val frac = (milli % Money.MILLI).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }
}
