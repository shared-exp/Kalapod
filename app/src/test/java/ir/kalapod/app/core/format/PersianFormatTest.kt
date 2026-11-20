package ir.kalapod.app.core.format

import ir.kalapod.app.core.jalali.JalaliDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PersianFormatTest {
    @Test fun numbers() {
        assertEquals("۰", PersianFormat.number(0))
        assertEquals("۱۲۳", PersianFormat.number(123))
        assertEquals("۱٬۲۰۰٬۰۰۰", PersianFormat.number(1_200_000))
        assertEquals("-۱٬۰۰۰", PersianFormat.number(-1000))
    }

    @Test fun money() {
        assertEquals("۱۸۰٬۰۰۰ تومان", PersianFormat.money(1_800_000, Currency.TOMAN))
        assertEquals("۱٬۸۰۰٬۰۰۰ ریال", PersianFormat.money(1_800_000, Currency.RIAL))
    }

    @Test fun quantity() {
        assertEquals("۲٫۵", PersianFormat.quantity(2_500))
        assertEquals("۱۰", PersianFormat.quantity(10_000))
        assertEquals("۰٫۰۵", PersianFormat.quantity(50))
        assertEquals("-۱٫۲۵", PersianFormat.quantity(-1_250))
    }

    @Test fun asciiDigits() {
        assertEquals("12a٫3", PersianFormat.toAsciiDigits("۱۲a٫٣"))
    }

    @Test fun dates() {
        assertEquals("۱۴۰۵/۰۸/۲۸", PersianFormat.date(JalaliDate(1405, 8, 28)))
        assertEquals("پنجشنبه ۲۸ آبان ۱۴۰۵", PersianFormat.longDate(JalaliDate(1405, 8, 28)))
    }
}
