package ir.kalapod.app.core.jalali

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JalaliDateTest {
    @Test fun knownDates() {
        assertEquals(JalaliDate(1403, 1, 1), JalaliDate.of(LocalDate.of(2024, 3, 20)))
        assertEquals(JalaliDate(1404, 1, 1), JalaliDate.of(LocalDate.of(2025, 3, 21)))
        assertEquals(JalaliDate(1405, 8, 28), JalaliDate.of(LocalDate.of(2026, 11, 19)))
        assertEquals(JalaliDate(1357, 11, 22), JalaliDate.of(LocalDate.of(1979, 2, 11)))
        assertEquals(JalaliDate(1348, 10, 11), JalaliDate.of(LocalDate.of(1970, 1, 1)))
    }

    @Test fun leapYears() {
        assertTrue(JalaliDate.isLeap(1403))
        assertFalse(JalaliDate.isLeap(1404))
        assertEquals(LocalDate.of(2025, 3, 20), JalaliDate(1403, 12, 30).toLocalDate())
    }

    @Test fun roundTripEveryDayForDecades() {
        var d = LocalDate.of(1990, 1, 1)
        var prev: JalaliDate? = null
        while (d.year < 2060) {
            val j = JalaliDate.of(d)
            assertEquals(d, j.toLocalDate())
            if (prev != null) assertTrue(j > prev)
            prev = j
            d = d.plusDays(1)
        }
    }

    @Test fun weekday() {
        // 2026-11-19 is a Thursday → پنجشنبه (index 6)
        assertEquals(6, JalaliDate(1405, 8, 28).dayOfWeek)
        assertEquals(1, JalaliDate.of(LocalDate.of(2026, 11, 14)).dayOfWeek) // Saturday
    }
}
