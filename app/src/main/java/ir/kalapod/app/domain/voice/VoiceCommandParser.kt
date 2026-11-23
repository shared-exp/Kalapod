package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit

enum class VoiceOperation { SALE, PURCHASE, RETURN }
enum class VoicePayment { CASH, CARD, CREDIT }

/** One item heard in a command. Quantities in milli-units; prices in RIAL. Null = not said. */
data class ParsedItem(
    val name: String,
    val quantityMilli: Long?,
    val unit: ProductUnit?,
    val unitPrice: Long?,
    /** Unit the price refers to ("کیلویی" → KILOGRAM) when it differs from [unit]. */
    val priceUnit: ProductUnit?,
    val totalPrice: Long?,
)

data class ParsedCommand(
    val operation: VoiceOperation?,
    val items: List<ParsedItem>,
    val partyName: String?,
    val payment: VoicePayment?,
    val raw: String,
)

/**
 * Rule-based Persian shop-command parser. Deterministic and offline, so it's testable and never
 * invents data: anything it can't find stays null and the UI asks only for that.
 *
 * Examples it understands:
 *  - «امروز ۱۰ کیلو برنج هاشمی خریدم، کیلویی ۱۸۰ هزار تومان»
 *  - «سه تا روغن لادن فروختم، دونهای ۹۰ هزار تومان»
 *  - «سه تا روغن و دو بسته ماکارونی فروختم»
 */
object VoiceCommandParser {

    private val unitWords = mapOf(
        "کیلو" to ProductUnit.KILOGRAM, "کیلوگرم" to ProductUnit.KILOGRAM, "کیلوی" to ProductUnit.KILOGRAM,
        "گرم" to ProductUnit.GRAM, "مثقال" to null,
        "تا" to ProductUnit.PIECE, "عدد" to ProductUnit.PIECE, "دونه" to ProductUnit.PIECE, "دانه" to ProductUnit.PIECE,
        "شیشه" to ProductUnit.PIECE, "قوطی" to ProductUnit.PIECE, "بطری" to ProductUnit.PIECE, "کیسه" to ProductUnit.PIECE,
        "بسته" to ProductUnit.PACK, "پاکت" to ProductUnit.PACK,
        "کارتن" to ProductUnit.CARTON, "جعبه" to ProductUnit.CARTON, "شل" to ProductUnit.CARTON,
        "لیتر" to ProductUnit.LITER, "متر" to ProductUnit.METER,
    ).filterValues { it != null }.mapValues { it.value!! }

    /** "کیلویی", "دونهای", "عددی", "تایی", "بستهای"… → price per that unit. */
    private val pricePerWords = mapOf(
        "کیلویی" to ProductUnit.KILOGRAM, "کیلوئی" to ProductUnit.KILOGRAM, "گرمی" to ProductUnit.GRAM,
        "عددی" to ProductUnit.PIECE, "تایی" to ProductUnit.PIECE, "دونهای" to ProductUnit.PIECE, "دانهای" to ProductUnit.PIECE,
        "بستهای" to ProductUnit.PACK, "کارتنی" to ProductUnit.CARTON, "لیتری" to ProductUnit.LITER, "متری" to ProductUnit.METER,
        "دونهای" to ProductUnit.PIECE,
    )
    private val priceMarkers = setOf("فی", "قیمت", "قیمتش", "هرکدوم", "هرکدام", "هرکدومش")
    private val totalMarkers = setOf("جمعا", "کلا", "مجموعا", "جمعاً", "کلاً", "مجموعاً", "همش", "همهاش", "رویهم", "رویهم")
    private val currencyWords = mapOf("تومان" to false, "تومن" to false, "تومنی" to false, "ریال" to true, "ریالی" to true)
    private val partyTitles = setOf("آقای", "اقای", "خانم", "اقا", "آقا", "حاج", "حاجی", "مشتری", "پخش", "شرکت", "فروشگاه", "بنکداری", "عمدهفروشی", "مغازه")
    private val fillers = setOf(
        "امروز", "دیروز", "الان", "من", "ما", "هم", "رو", "را", "که", "دیگه", "دیگر", "هست", "بود", "برای", "این", "اون",
        "یکجا", "مقدار", "از", "به", "با", "هر", "تومنی", "ای", "و", "اینو", "اونو", "هاش", "مشتری",
    )

    fun parse(text: String): ParsedCommand {
        val raw = text
        var tokens = PackSize.mergeCc(PersianText.tokens(joinSuffixes(text)))

        val operation = detectOperation(tokens)
        val payment = detectPayment(tokens)
        val (party, rest) = extractParty(tokens, operation)
        tokens = rest.filterNot { isVerbOrPaymentWord(it) }

        return ParsedCommand(operation, buildItems(tokens), party, payment, raw)
    }

    /** Re-attaches suffixes that ZWNJ normalization splits ("دونه ای" → "دونهای") before tokenizing. */
    private fun joinSuffixes(text: String): String =
        PersianText.normalize(text).replace(Regex("(\\S+) ای(?=\\s|$)"), "$1ای").replace(Regex("(\\S+) اش(?=\\s|$)"), "$1اش")

    // ---------------- operation / payment ----------------

    private fun detectOperation(t: List<String>): VoiceOperation? {
        val joined = " " + t.joinToString(" ") + " "
        return when {
            listOf("مرجوع", "برگشت", "برگردوند", "برگرداند", "پس داد", "پس آورد", "پس اورد", "عودت").any { it in joined } -> VoiceOperation.RETURN
            t.any { it.startsWith("فروخت") || it == "فروش" || it == "فروختم" || it.startsWith("بفروش") } -> VoiceOperation.SALE
            t.any { it.startsWith("خرید") || it.startsWith("خریدار") || it == "گرفتم" || it == "آوردم" || it == "اوردم" } -> VoiceOperation.PURCHASE
            else -> null
        }
    }

    private fun detectPayment(t: List<String>): VoicePayment? = when {
        t.any { it == "نسیه" || it == "نسیهای" || it == "قرض" } -> VoicePayment.CREDIT
        t.any { it == "کارت" || it == "کارتخوان" || it == "کارتخوان" || it == "پوز" } -> VoicePayment.CARD
        t.any { it == "نقد" || it == "نقدی" || it == "نقدا" } -> VoicePayment.CASH
        else -> null
    }

    private fun isVerbOrPaymentWord(w: String) =
        w.startsWith("فروخت") || w == "فروش" || w.startsWith("بفروش") || w.startsWith("خریدم") || w == "خریدیم" ||
            w == "خرید" || w == "خریداری" || w == "کردم" || w == "کردیم" || w == "شد" || w == "گرفتم" || w == "آوردم" ||
            w == "اوردم" || w.startsWith("مرجوع") || w == "برگشت" || w == "برگشتی" || w.startsWith("برگردوند") ||
            w.startsWith("برگرداند") || w == "پس" || w == "داد" || w == "دادم" || w == "آورد" || w == "اورد" ||
            w == "عودت" || w == "نسیه" || w == "نسیهای" || w == "قرض" || w == "کارت" || w == "کارتخوان" ||
            w == "کارتخوان" || w == "پوز" || w == "نقد" || w == "نقدی" || w == "نقدا" || w == "حساب"

    /** "به آقای رضایی", "از پخش البرز" → party name; removes those tokens. */
    private fun extractParty(t: List<String>, op: VoiceOperation?): Pair<String?, List<String>> {
        for (i in t.indices) {
            if (t[i] !in partyTitles) continue
            val nameTokens = mutableListOf(t[i])
            var j = i + 1
            while (j < t.size && nameTokens.size < 3 && t[j] != PersianText.COMMA && !SpokenNumbers.isNumberWord(t[j]) &&
                !isVerbOrPaymentWord(t[j]) && t[j] !in unitWords && t[j] != "و"
            ) { nameTokens += t[j]; j++ }
            if (nameTokens.size == 1) continue
            val from = if (i > 0 && (t[i - 1] == "به" || t[i - 1] == "از")) i - 1 else i
            // Titles like "مشتری"/"پخش" are kept only when meaningful for suppliers/companies.
            val name = nameTokens.filterIndexed { k, w -> !(k == 0 && w == "مشتری") }.joinToString(" ")
            return name to (t.subList(0, from) + t.subList(j, t.size))
        }
        return null to t
    }

    // ---------------- items ----------------

    private class Builder {
        val name = mutableListOf<String>()
        var qty: Long? = null
        var unit: ProductUnit? = null
        var unitPrice: Long? = null
        var priceUnit: ProductUnit? = null
        var total: Long? = null
        val isEmpty get() = name.isEmpty() && qty == null && unitPrice == null && total == null
        fun build() = ParsedItem(
            name = name.filterNot { it in fillers }.joinToString(" ").trim(),
            quantityMilli = qty, unit = unit, unitPrice = unitPrice,
            priceUnit = priceUnit?.takeIf { it != unit }, totalPrice = total,
        )
    }

    private fun buildItems(t: List<String>): List<ParsedItem> {
        val items = mutableListOf<Builder>()
        var cur = Builder()
        fun flush() { if (!cur.isEmpty) items += cur; cur = Builder() }
        /** The item a trailing price clause belongs to ("…, کیلویی ۱۸۰ هزار"). */
        fun target(): Builder = if (cur.name.isEmpty() && cur.qty == null && items.isNotEmpty()) items.last() else cur

        var i = 0
        while (i < t.size) {
            val w = t[i]
            when {
                w == PersianText.COMMA -> { i++; if (cur.name.isNotEmpty() && cur.qty != null) flush() }

                // "و" before a number starts a new item: "سه تا روغن و دو بسته ماکارونی"
                w == "و" -> {
                    if (i + 1 < t.size && SpokenNumbers.isNumberWord(t[i + 1]) && cur.name.isNotEmpty()) flush()
                    i++
                }

                // Unit price: "کیلویی ۱۸۰ هزار", "هر کیلو ۱۸۰", "دونهای ۹۰", "به قیمت هر عدد ۲۵ هزار", "فی ۹۰"
                w in pricePerWords || w in priceMarkers || (w == "هر" && i + 1 < t.size && t[i + 1] in unitWords) -> {
                    var j = i
                    var per: ProductUnit? = pricePerWords[w]
                    while (j < t.size && (t[j] in priceMarkers || t[j] in pricePerWords || t[j] == "هر" || t[j] == "به" ||
                            (t[j] in unitWords && j > i && t[j - 1] == "هر"))
                    ) {
                        per = pricePerWords[t[j]] ?: (if (t[j] in unitWords && t[j - 1] == "هر") unitWords[t[j]] else per)
                        j++
                    }
                    val price = readPrice(t, j)
                    if (price != null) {
                        target().apply { unitPrice = price.first; priceUnit = per ?: priceUnit }
                        i = price.second
                    } else {
                        i = j
                    }
                }

                w in totalMarkers -> {
                    val price = readPrice(t, i + 1)
                    if (price != null) { target().total = price.first; i = price.second } else i++
                }

                SpokenNumbers.isNumberWord(w) -> {
                    val num = SpokenNumbers.parse(t, i, unitWords.keys)
                    if (num == null) { cur.name += w; i++; continue }
                    val next = t.getOrNull(num.next)
                    // «۵۰۰ گرمی», «نیم کیلویی», «۱.۵ لیتری»: the pack size is part of the product's name,
                    // not a quantity. (Word before the number — «کیلویی ۱۸۰ هزار» — stays a unit price.)
                    if (next != null && PackSize.isSizeWord(next) && next !in unitWords && num.milli < 20_000_000L) {
                        val whole = num.milli / 1000; val frac = num.milli % 1000
                        cur.name += if (frac == 0L) whole.toString() else "$whole.${frac.toString().padStart(3, '0').trimEnd('0')}"
                        cur.name += next
                        i = num.next + 1
                        continue
                    }
                    val looksLikePrice = next != null && (next in currencyWords) ||
                        (num.milli >= 1_000_000L && next !in unitWords) // ≥1000 without a unit: a price
                    when {
                        next in unitWords -> {
                            // A new quantity after a completed item starts the next item.
                            if (cur.qty != null && cur.name.isNotEmpty()) flush()
                            if (cur.qty != null && cur.name.isEmpty() && items.isNotEmpty()) flush()
                            cur.qty = num.milli; cur.unit = unitWords.getValue(next!!)
                            i = num.next + 1
                        }
                        looksLikePrice && (cur.qty != null || cur.name.isNotEmpty() || items.isNotEmpty()) -> {
                            val price = readPrice(t, i)!!
                            val b = target()
                            // A bare price after a multi-unit quantity is ambiguous; treat as unit price.
                            b.unitPrice = price.first
                            i = price.second
                        }
                        // «۱۰ تا رب ۷۰۰ چین چین» = «۱۰ تا رب چین چین ۷۰۰»: once an item has its quantity, a bare
                        // number (no unit after it, not a price) belongs to the name. The next item starts at
                        // a number followed by a unit («۲۰ تا روغن …»), handled above.
                        cur.qty != null || (cur.name.isNotEmpty() && items.isNotEmpty()) -> {
                            cur.name += PackSize.bare(num.milli)
                            i = num.next
                        }
                        else -> {
                            if (cur.qty != null && cur.name.isNotEmpty()) flush()
                            cur.qty = num.milli
                            i = num.next
                        }
                    }
                }

                w in currencyWords -> i++
                w in fillers -> i++
                else -> { cur.name += w; i++ }
            }
        }
        flush()
        return items.map { it.build() }.filter { it.name.isNotEmpty() || it.quantityMilli != null }
    }

    /** Reads "N [هزار…] [تومان|ریال]" at [start] → (rial, nextIndex). Toman is the default. */
    private fun readPrice(t: List<String>, start: Int): Pair<Long, Int>? {
        val num = SpokenNumbers.parse(t, start, unitWords.keys) ?: return null
        var next = num.next
        var isRial = false
        if (next < t.size && t[next] in currencyWords) { isRial = currencyWords.getValue(t[next]); next++ }
        val whole = num.milli / 1000
        return (if (isRial) whole else whole * 10) to next
    }
}
