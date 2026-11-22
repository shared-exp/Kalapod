package ir.kalapod.app.domain.voice

import ir.kalapod.app.domain.voice.ProductMatcher.Candidate
import ir.kalapod.app.domain.voice.ProductMatcher.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech-recognition mistakes a shopkeeper will actually hit: the product must still be found. */
class SoundAlikeMatchTest {
    private val catalog = listOf(
        Candidate(1, "رب گوجه فرنگی چین چین"),
        Candidate(2, "رب گوجه فرنگی تبرک"),
        Candidate(3, "رب انار"),
        Candidate(4, "گوجه فرنگی"),
        Candidate(5, "پنیر صباح"),
        Candidate(6, "ماست کاله"),
        Candidate(7, "شیر پاستوریزه"),
        Candidate(8, "چای احمد"),
        Candidate(9, "زعفران قائنات"),
    )

    private fun sure(spoken: String): Long {
        val m = ProductMatcher.match(spoken, catalog)
        assertTrue("«$spoken» → $m", m is Match.Sure)
        return (m as Match.Sure).candidate.id
    }

    @Test fun userExample_missingLetterAndShForCh() = assertEquals(1L, sure("رب گوجه ارنگی شین شین"))

    @Test fun brandDecidesBetweenSimilarProducts() {
        assertEquals(2L, sure("رب گوجه تبرک"))
        assertEquals(1L, sure("رب چین چین"))
        assertEquals(1L, sure("گوجه چین چین"))
    }

    @Test fun soundAlikeLetters() {
        assertEquals(5L, sure("پنیر سباح"))          // ص ↔ س
        assertEquals(6L, sure("ماست قاله"))          // ک ↔ ق heard
        assertEquals(9L, sure("زعفران غائنات"))      // ق ↔ غ
        assertEquals(9L, sure("ذعفران قاینات"))      // ز ↔ ذ, ئ ↔ ی
        assertEquals(8L, sure("چایی اهمد"))          // ح ↔ ه
    }

    @Test fun extraWordsAroundTheName() {
        assertEquals(1L, sure("رب گوجه فرنگی چین چین لطفا"))
        assertEquals(7L, sure("همون شیر پاستوریزه"))
    }

    @Test fun genuinelyAmbiguousStillAsks() {
        val m = ProductMatcher.match("رب گوجه", catalog)
        assertTrue(m is Match.Ambiguous)
        assertEquals(setOf(1L, 2L), (m as Match.Ambiguous).options.take(2).map { it.id }.toSet())
    }

    @Test fun learnedAliasWins() {
        val aliases = mapOf(ProductMatcher.fold("رب شین") to 1L)
        val m = ProductMatcher.match("رب شین", catalog, aliases)
        assertEquals(1L, (m as Match.Sure).candidate.id)
    }
}
