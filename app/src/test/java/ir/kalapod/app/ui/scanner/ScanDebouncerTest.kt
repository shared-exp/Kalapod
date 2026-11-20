package ir.kalapod.app.ui.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebouncerTest {
    @Test fun holdingSameCodeCountsOnce_rescanAfterPauseCountsAgain() {
        val d = ScanDebouncer(1_500)
        assertTrue(d.accept("A", 0))
        assertFalse(d.accept("A", 100))   // camera still sees it
        assertFalse(d.accept("A", 1_400)) // continuously seen → still the same scan
        assertTrue(d.accept("A", 3_000))  // pause, then scanned again
        assertTrue(d.accept("B", 3_100))  // different product immediately
        assertTrue(d.accept("A", 3_200))
    }
}
