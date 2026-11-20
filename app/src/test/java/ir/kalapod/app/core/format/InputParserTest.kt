package ir.kalapod.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputParserTest {
    @Test fun money() {
        assertEquals(1_800_000L, InputParser.money("۱۸۰٬۰۰۰", Currency.TOMAN))
        assertEquals(180_000L, InputParser.money("180,000", Currency.RIAL))
        assertNull(InputParser.money("", Currency.TOMAN))
        assertNull(InputParser.money("-5", Currency.TOMAN))
        assertNull(InputParser.money("12.5", Currency.TOMAN))
    }

    @Test fun quantity() {
        assertEquals(2_500L, InputParser.quantity("۲٫۵"))
        assertEquals(2_500L, InputParser.quantity("2.5"))
        assertEquals(10_000L, InputParser.quantity("10"))
        assertEquals(50L, InputParser.quantity(".05"))
        assertNull(InputParser.quantity("1.2345"))
        assertNull(InputParser.quantity("1.2.3"))
        assertNull(InputParser.quantity("abc"))
    }

    @Test fun editRoundTrip() {
        assertEquals("2.5", InputParser.quantityForEdit(2_500))
        assertEquals("180000", InputParser.moneyForEdit(1_800_000, Currency.TOMAN))
    }
}
