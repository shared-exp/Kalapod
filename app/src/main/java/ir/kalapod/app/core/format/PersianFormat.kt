package ir.kalapod.app.core.format

import ir.kalapod.app.core.jalali.JalaliDate
import ir.kalapod.app.domain.finance.Money

enum class Currency(val label: String) { TOMAN("تومان"), RIAL("ریال") }

/** Formatting for user-facing text: Persian digits, Persian separators, Jalali dates. */
object PersianFormat {
    private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private const val GROUP_SEP = '٬'
    private const val DECIMAL_SEP = '٫'

    fun digits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
    }

    /** Converts Persian and Arabic-Indic digits to ASCII; leaves everything else intact. */
    fun toAsciiDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            },
        )
    }

    fun number(n: Long): String {
        val abs = Math.abs(n).toString()
        val grouped = abs.reversed().chunked(3).joinToString(GROUP_SEP.toString()).reversed()
        return digits(if (n < 0) "-$grouped" else grouped)
    }

    /** Money stored in rial, shown in the chosen currency. */
    fun money(rial: Long, currency: Currency, withUnit: Boolean = true): String {
        val v = if (currency == Currency.TOMAN) Money.rialToToman(rial) else rial
        return if (withUnit) "${number(v)} ${currency.label}" else number(v)
    }

    /** Milli-unit quantity as a decimal, trailing zeros trimmed: 2500 → ۲٫۵ */
    fun quantity(milli: Long): String {
        val sign = if (milli < 0) "-" else ""
        val abs = Math.abs(milli)
        val whole = abs / Money.MILLI
        val frac = (abs % Money.MILLI).toString().padStart(3, '0').trimEnd('0')
        val wholeStr = number(whole)
        return digits(sign) + if (frac.isEmpty()) wholeStr else "$wholeStr$DECIMAL_SEP${digits(frac)}"
    }

    /**
     * Isolates a left-to-right code (invoice number, barcode) inside RTL text so the bidi algorithm
     * doesn't scramble it ("S-1405-000012" would otherwise show as "000012-1405-S").
     */
    fun ltr(s: String): String = "⁦" + toAsciiDigits(s) + "⁩" // Latin digits are strongly LTR

    fun date(d: JalaliDate): String = digits(d.toString())

    fun longDate(d: JalaliDate): String =
        "${JalaliDate.WEEKDAY_NAMES[d.dayOfWeek - 1]} ${digits(d.day.toString())} ${JalaliDate.MONTH_NAMES[d.month - 1]} ${digits(d.year.toString())}"
}
