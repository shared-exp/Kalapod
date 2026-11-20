package ir.kalapod.app.domain.voice

import ir.kalapod.app.domain.voice.ProductMatcher.Candidate
import ir.kalapod.app.domain.voice.ProductMatcher.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductMatcherTest {
    private val catalog = listOf(
        Candidate(1, "روغن لادن ۱.۵ لیتری"),
        Candidate(2, "برنج هاشمی"),
        Candidate(3, "برنج طارم"),
        Candidate(4, "ماکارونی زر"),
        Candidate(5, "نوشابه خانواده کوکاکولا"),
        Candidate(6, "پسته اکبری"),
        Candidate(7, "آب معدنی دماوند"),
        Candidate(8, "چای احمد"),
    )

    private fun sure(spoken: String): Long = (ProductMatcher.match(spoken, catalog) as Match.Sure).candidate.id

    @Test fun exactAndPartialNames() {
        assertEquals(2L, sure("برنج هاشمی"))
        assertEquals(1L, sure("روغن لادن"))
        assertEquals(1L, sure("روغن"))          // only one oil
        assertEquals(4L, sure("ماکارونی"))
        assertEquals(6L, sure("پسته اکبری"))
        assertEquals(5L, sure("نوشابه خانواده"))
    }

    @Test fun speechNoiseAndColloquial() {
        assertEquals(4L, sure("ماکارانی"))
        assertEquals(7L, sure("اب معدنی"))      // آ written as ا
        assertEquals(8L, sure("چایی احمد"))
        assertEquals(2L, sure("برنج هاشمي"))   // Arabic ي
    }

    @Test fun ambiguousWhenSeveralFit() {
        val m = ProductMatcher.match("برنج", catalog)
        assertTrue(m is Match.Ambiguous)
        assertEquals(setOf(2L, 3L), (m as Match.Ambiguous).options.take(2).map { it.id }.toSet())
    }

    @Test fun noneWhenUnknown() {
        assertTrue(ProductMatcher.match("پودر لباسشویی", catalog) is Match.None)
        assertTrue(ProductMatcher.match("", catalog) is Match.None)
    }
}
