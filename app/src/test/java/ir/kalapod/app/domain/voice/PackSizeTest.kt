package ir.kalapod.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PackSizeTest {
    private fun n(s: String) = PackSize.normalize(PersianText.tokens(s)).joinToString(" ")

    @Test fun writtenAndSpokenFormsAgree() {
        assertEquals("رب چین چین 250g", n("رب چین چین ۲۵۰ گرمی"))
        assertEquals("رب چین چین 250g", n("رب چین چین دویست و پنجاه گرمی"))
        assertEquals("رب چین چین 250g", n("رب چین چین 250گرمی"))
        assertEquals("رب چین چین 250g", n("رب چین چین 250g"))
        assertEquals("برنج 500g", n("برنج نیم کیلویی"))
        assertEquals("روغن لادن 1500ml", n("روغن لادن ۱.۵ لیتری"))
        assertEquals("روغن لادن 1500ml", n("روغن لادن یک و نیم لیتری"))
        assertEquals("نوشابه 300ml", n("نوشابه ۳۰۰ سی‌سی"))
    }

    @Test fun bareUnitIsNotASize() {
        assertEquals("500 گرم برنج", n("۵۰۰ گرم برنج"))
        assertEquals("دو تا رب", n("دو تا رب"))
    }

    @Test fun amounts() {
        assertEquals(setOf("250g"), PackSize.sizesIn(listOf("رب", "250g")))
        assertEquals(1500L, PackSize.amount("1500ml"))
    }
}
