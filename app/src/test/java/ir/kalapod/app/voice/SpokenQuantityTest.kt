package ir.kalapod.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenQuantityTest {
    @Test fun findsFirstNumber() {
        assertEquals(12_500L, spokenQuantity("دوازده و نیم کیلو"))
        assertEquals(7_000L, spokenQuantity("موجودی ۷ تاست"))
        assertEquals(30_000L, spokenQuantity("سی"))
        assertNull(spokenQuantity("هیچی"))
    }
}
