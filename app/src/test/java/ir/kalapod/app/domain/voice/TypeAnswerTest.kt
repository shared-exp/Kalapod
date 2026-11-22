package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeAnswerTest {
    private fun p(id: Long, name: String) = CatalogProduct(id, name, ProductUnit.PIECE, 300_000, 250_000, 5_000)
    private val rob = listOf(p(1, "رب گوجه فرنگی چین چین"), p(2, "رب گوجه فرنگی تبرک"), p(3, "رب گوجه فرنگی دلپذیر"))

    @Test fun brandOnly() {
        assertEquals(1L, TypeAnswer.pick("چین چین", rob)?.id)
        assertEquals(2L, TypeAnswer.pick("تبرک", rob)?.id)
        assertEquals(3L, TypeAnswer.pick("همون دلپذیر", rob)?.id)
    }

    @Test fun misheardBrand() {
        assertEquals(1L, TypeAnswer.pick("شین شین", rob)?.id)
        assertEquals(2L, TypeAnswer.pick("طبرک", rob)?.id)
    }

    @Test fun fullNameAndOrdinals() {
        assertEquals(2L, TypeAnswer.pick("رب گوجه فرنگی تبرک", rob)?.id)
        assertEquals(1L, TypeAnswer.pick("اولی", rob)?.id)
        assertEquals(3L, TypeAnswer.pick("سومی رو بده", rob)?.id)
    }

    @Test fun unrelatedAnswerIsNotGuessed() {
        assertNull(TypeAnswer.pick("نوشابه", rob))
        assertNull(TypeAnswer.pick("", rob))
    }

    @Test fun commonPrefix() {
        assertEquals(3, TypeAnswer.commonPrefixWords(rob.map { it.name }))
        assertEquals(0, TypeAnswer.commonPrefixWords(listOf("روغن", "برنج")))
    }
}
