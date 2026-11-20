package ir.kalapod.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenNumbersTest {
    private fun n(text: String): Long? = SpokenNumbers.parse(PersianText.tokens(text), 0)?.milli?.div(1000)
    private fun milli(text: String): Long? = SpokenNumbers.parse(PersianText.tokens(text), 0)?.milli

    @Test fun digitsAndWords() {
        assertEquals(10L, n("10"))
        assertEquals(10L, n("۱۰"))
        assertEquals(3L, n("سه"))
        assertEquals(1L, n("یه"))
        assertEquals(25L, n("بیست و پنج"))
        assertEquals(180L, n("صد و هشتاد"))
        assertEquals(350L, n("سیصد و پنجاه"))
        assertEquals(300L, n("سه صد"))
    }

    @Test fun scales() {
        assertEquals(180_000L, n("۱۸۰ هزار"))
        assertEquals(180_000L, n("صد و هشتاد هزار"))
        assertEquals(25_000L, n("بیست و پنج هزار"))
        assertEquals(1_000L, n("هزار"))
        assertEquals(1_200_000L, n("یک میلیون و دویست"))         // implied هزار
        assertEquals(1_200_000L, n("یک میلیون و دویست هزار"))
        assertEquals(2_500_000L, n("دو و نیم میلیون"))
        assertEquals(1_250_000L, n("یک میلیون و دویست و پنجاه"))
    }

    @Test fun halves() {
        assertEquals(1_500L, milli("یک و نیم"))
        assertEquals(500L, milli("نیم"))
        assertEquals(2_500L, milli("2.5"))
        assertEquals(2_500L, milli("۲٫۵"))
    }

    @Test fun stopsAtNonNumber() {
        val t = PersianText.tokens("سه تا روغن و دو بسته")
        val r = SpokenNumbers.parse(t, 0)!!
        assertEquals(3_000L, r.milli)
        assertEquals(1, r.next)
        assertNull(SpokenNumbers.parse(t, 2))
    }

    @Test fun andBetweenNumberAndWordIsNotSwallowed() {
        val t = PersianText.tokens("سه و روغن")
        assertEquals(1, SpokenNumbers.parse(t, 0)!!.next)
    }
}
