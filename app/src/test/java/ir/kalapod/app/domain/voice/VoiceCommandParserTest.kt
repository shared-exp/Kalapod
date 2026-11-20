package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    private fun p(s: String) = VoiceCommandParser.parse(s)

    // ---- the four examples from SPEC §3 ----

    @Test fun spec1_purchaseRiceWithPerKiloPrice() {
        val c = p("امروز ۱۰ کیلو برنج هاشمی خریدم، کیلویی ۱۸۰ هزار تومان.")
        assertEquals(VoiceOperation.PURCHASE, c.operation)
        val i = c.items.single()
        assertEquals("برنج هاشمی", i.name)
        assertEquals(10_000L, i.quantityMilli)
        assertEquals(ProductUnit.KILOGRAM, i.unit)
        assertEquals(1_800_000L, i.unitPrice)
    }

    @Test fun spec2_saleOilPerPiece() {
        val c = p("سه تا روغن لادن فروختم، دونه‌ای ۹۰ هزار تومان.")
        assertEquals(VoiceOperation.SALE, c.operation)
        val i = c.items.single()
        assertEquals("روغن لادن", i.name)
        assertEquals(3_000L, i.quantityMilli)
        assertEquals(ProductUnit.PIECE, i.unit)
        assertEquals(900_000L, i.unitPrice)
    }

    @Test fun spec3_pistachioMillionAndTwoHundred() {
        val c = p("۵ کیلو پسته اکبری کیلویی یک میلیون و دویست خریدم")
        assertEquals(VoiceOperation.PURCHASE, c.operation)
        val i = c.items.single()
        assertEquals("پسته اکبری", i.name)
        assertEquals(5_000L, i.quantityMilli)
        assertEquals(12_000_000L, i.unitPrice)
    }

    @Test fun spec4_cartonsWithPerPiecePrice() {
        val c = p("دو کارتن نوشابه خانواده به قیمت هر عدد ۲۵ هزار تومان فروختم")
        assertEquals(VoiceOperation.SALE, c.operation)
        val i = c.items.single()
        assertEquals("نوشابه خانواده", i.name)
        assertEquals(2_000L, i.quantityMilli)
        assertEquals(ProductUnit.CARTON, i.unit)
        assertEquals(250_000L, i.unitPrice)
        assertEquals(ProductUnit.PIECE, i.priceUnit) // price is per piece, quantity is cartons → UI must ask/convert
    }

    @Test fun spec_multiItemCart() {
        val c = p("سه تا روغن و دو بسته ماکارونی فروختم")
        assertEquals(VoiceOperation.SALE, c.operation)
        assertEquals(2, c.items.size)
        assertEquals("روغن", c.items[0].name)
        assertEquals(3_000L, c.items[0].quantityMilli)
        assertEquals("ماکارونی", c.items[1].name)
        assertEquals(2_000L, c.items[1].quantityMilli)
        assertEquals(ProductUnit.PACK, c.items[1].unit)
        assertNull(c.items[0].unitPrice)
    }

    // ---- more phrasing ----

    @Test fun pricesPerItemInMultiItem() {
        val c = p("دو تا شیر دونه‌ای ۳۰ هزار و یک کیلو پنیر کیلویی ۲۰۰ هزار فروختم")
        assertEquals(2, c.items.size)
        assertEquals(300_000L, c.items[0].unitPrice)
        assertEquals("پنیر", c.items[1].name)
        assertEquals(1_000L, c.items[1].quantityMilli)
        assertEquals(2_000_000L, c.items[1].unitPrice)
    }

    @Test fun halfKiloAndGrams() {
        val c = p("نیم کیلو زعفران و ۵۰۰ گرم چای فروختم")
        assertEquals(500L, c.items[0].quantityMilli)
        assertEquals(ProductUnit.KILOGRAM, c.items[0].unit)
        assertEquals("چای", c.items[1].name)
        assertEquals(500_000L, c.items[1].quantityMilli)
        assertEquals(ProductUnit.GRAM, c.items[1].unit)
    }

    @Test fun totalPrice() {
        val i = p("چهار تا تن ماهی فروختم جمعا ۴۰۰ هزار تومان").items.single()
        assertEquals("تن ماهی", i.name)
        assertEquals(4_000_000L, i.totalPrice)
        assertNull(i.unitPrice)
    }

    @Test fun customerAndCredit() {
        val c = p("دو کیلو برنج نسیه به آقای رضایی فروختم")
        assertEquals(VoiceOperation.SALE, c.operation)
        assertEquals(VoicePayment.CREDIT, c.payment)
        assertEquals("آقای رضایی", c.partyName)
        assertEquals("برنج", c.items.single().name)
    }

    @Test fun supplier() {
        val c = p("از پخش البرز ده کارتن آب معدنی خریدم")
        assertEquals("پخش البرز", c.partyName)
        assertEquals(VoiceOperation.PURCHASE, c.operation)
        assertEquals("آب معدنی", c.items.single().name)
        assertEquals(10_000L, c.items.single().quantityMilli)
    }

    @Test fun returnOperation() {
        val c = p("مشتری یک بسته ماکارونی برگردوند")
        assertEquals(VoiceOperation.RETURN, c.operation)
        assertEquals("ماکارونی", c.items.single().name)
    }

    @Test fun rialAndCard() {
        val c = p("یه دونه نوشابه فروختم ۲۵۰ هزار ریال با کارت")
        assertEquals(VoicePayment.CARD, c.payment)
        assertEquals(250_000L, c.items.single().unitPrice)
        assertEquals(1_000L, c.items.single().quantityMilli)
    }

    @Test fun noOperationStillParsesItems() {
        val c = p("سه تا ماست")
        assertNull(c.operation)
        assertEquals("ماست", c.items.single().name)
    }

    @Test fun arabicLettersNormalized() {
        assertEquals("کیک", p("دو تا كيك فروختم").items.single().name)
    }
}
