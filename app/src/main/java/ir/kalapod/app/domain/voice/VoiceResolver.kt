package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.domain.finance.Money

/** What the resolver needs to know about a catalog product. */
data class CatalogProduct(
    val id: Long,
    val name: String,
    val unit: ProductUnit,
    val salePrice: Long,
    val lastPurchasePrice: Long,
    /** Current stock (milli-units), shown next to each option when the user picks a type. */
    val stockMilli: Long = 0,
    /** Pieces per carton/box (milli), if known for this product. */
    val caseSizeMilli: Long? = null,
)

/** One heard item mapped onto the catalog, ready to drop into a cart (after the user confirms). */
data class ResolvedLine(
    val heard: ParsedItem,
    /** Null until a product is chosen (ambiguous) or defined (unknown). */
    val product: CatalogProduct?,
    val options: List<CatalogProduct>,
    val quantityMilli: Long,
    /** Unit price in rial per product unit; null = use the product's default price. */
    val unitPrice: Long?,
    /** Short Persian notes the user should see ("قیمت گفته نشد…"). */
    val notes: List<String>,
    /** A general name with several types in stock: the type must be chosen, never assumed or learned. */
    val generic: Boolean = false,
    /** Purchases: what was heard may be a product we don't stock yet; offer «کالای جدید». */
    val allowNew: Boolean = false,
    /** Said in cartons/boxes: how many cartons (milli). [quantityMilli] is then in pieces. */
    val casesMilli: Long? = null,
    /** Pieces per carton (milli): said («۱۲ تایی») or known for the product. */
    val caseSizeMilli: Long? = null,
    /** Price of one carton, when the price was said per carton. */
    val casePrice: Long? = null,
) {
    /** Said in cartons but the carton size is not known yet: ask «هر کارتن چند تا؟». */
    val needsCaseSize get() = product != null && casesMilli != null && caseSizeMilli == null
    val needsChoice get() = product == null && options.isNotEmpty()
    val isUnknown get() = product == null && options.isEmpty()
}

/**
 * Maps parsed items to catalog products, converts units where it's unambiguous (g ↔ kg), and fills
 * sensible defaults so the user is asked only what really matters (which product, when unsure).
 */
object VoiceResolver {

    /** @param aliases learned spoken names (ProductMatcher.fold) → product id. */
    fun resolve(
        cmd: ParsedCommand, catalog: List<CatalogProduct>, forPurchase: Boolean,
        aliases: Map<String, Long> = emptyMap(),
    ): List<ResolvedLine> {
        val candidates = catalog.map { ProductMatcher.Candidate(it.id, it.name) }
        val byId = catalog.associateBy { it.id }
        return cmd.items.filter { it.name.isNotBlank() }.map { item ->
            when (val m = ProductMatcher.match(item.name, candidates, aliases)) {
                // A purchase may be a brand-new product: approximate (sound-alike) guesses become a question.
                is ProductMatcher.Match.Sure -> if (forPurchase && !nearExact(item.name, m.candidate, candidates, aliases)) {
                    ResolvedLine(
                        item, null, ProductMatcher.rank(item.name, candidates).filter { it.score >= 0.5 }.take(4)
                            .map { byId.getValue(it.candidate.id) },
                        item.quantityMilli ?: Money.MILLI, null, listOf("کدام «${item.shownName}»؟"), allowNew = true,
                    )
                } else {
                    fit(item, byId.getValue(m.candidate.id), forPurchase)
                }
                is ProductMatcher.Match.Ambiguous -> ResolvedLine(
                    item, null, m.options.map { byId.getValue(it.id) },
                    item.quantityMilli ?: Money.MILLI, null, listOf("کدام «${item.shownName}»؟"), generic = m.generic,
                    allowNew = forPurchase,
                )
                ProductMatcher.Match.None -> ResolvedLine(
                    item, null, emptyList(), item.quantityMilli ?: Money.MILLI, null,
                    listOf("«${item.shownName}» در کالاها نیست"),
                )
            }
        }
    }

    /** The spoken name is (almost) exactly the product's name, or a name the user taught us. */
    private fun nearExact(
        spoken: String, c: ProductMatcher.Candidate, all: List<ProductMatcher.Candidate>, aliases: Map<String, Long>,
    ): Boolean {
        if (aliases[ProductMatcher.fold(spoken)] == c.id) return true
        val s = ProductMatcher.rank(spoken, all).firstOrNull { it.candidate.id == c.id }?.score ?: 0.0
        return s >= NEAR_EXACT
    }

    /** Purchases only auto-match above this; below it the user confirms or says «new product». */
    private const val NEAR_EXACT = 0.97

    /** Re-fits a line after the user picks/defines its product. */
    fun choose(line: ResolvedLine, product: CatalogProduct, forPurchase: Boolean) = fit(line.heard, product, forPurchase)

    /** The user answered «هر کارتن چند تا؟». */
    fun withCaseSize(line: ResolvedLine, caseSizeMilli: Long, forPurchase: Boolean): ResolvedLine {
        val p = line.product ?: return line
        return fit(line.heard.copy(caseSizeMilli = caseSizeMilli), p, forPurchase)
    }

    private fun fit(item: ParsedItem, p: CatalogProduct, forPurchase: Boolean): ResolvedLine {
        if (item.unit == ProductUnit.CARTON && p.unit != ProductUnit.CARTON) return fitCases(item, p, forPurchase)
        val notes = mutableListOf<String>()
        var qty = item.quantityMilli ?: Money.MILLI.also { notes += "مقدار گفته نشد؛ ۱ در نظر گرفته شد" }
        val spokenUnit = item.unit
        if (spokenUnit != null && spokenUnit != p.unit && !(spokenUnit == ProductUnit.PIECE && p.unit != ProductUnit.KILOGRAM)) {
            val converted = convert(qty, spokenUnit, p.unit)
            if (converted != null) qty = converted else notes += "واحد گفته‌شده با واحد کالا فرق دارد؛ مقدار را بررسی کنید"
        }

        // Price: per-unit if said (converted to the product's unit), else derived from a total, else default.
        val priceUnit = item.priceUnit ?: spokenUnit ?: p.unit
        var price: Long? = item.unitPrice?.let { said ->
            when {
                priceUnit == p.unit -> said
                else -> convertPrice(said, priceUnit, p.unit) ?: run {
                    notes += "قیمت برای واحد دیگری گفته شد؛ قیمت را بررسی کنید"; null
                }
            }
        }
        if (price == null && item.totalPrice != null && qty > 0) {
            price = Money.divRoundHalfUp(item.totalPrice * Money.MILLI, qty)
        }
        if (price == null && item.unitPrice == null && item.totalPrice == null) {
            val def = if (forPurchase) p.lastPurchasePrice else p.salePrice
            if (def == 0L) notes += "قیمت گفته نشد"
        }
        return ResolvedLine(item, p, emptyList(), qty, price, notes)
    }

    /**
     * «۱۰ کارتن ۱۲ تایی کیک»: 10 cartons × 12 = 120 pieces in stock; the cart and invoice show cartons.
     * Without a size said or known for the product, the line waits for «هر کارتن چند تا؟».
     */
    private fun fitCases(item: ParsedItem, p: CatalogProduct, forPurchase: Boolean): ResolvedLine {
        val notes = mutableListOf<String>()
        val cases = item.quantityMilli ?: Money.MILLI.also { notes += "تعداد کارتن گفته نشد؛ ۱ در نظر گرفته شد" }
        val size = item.caseSizeMilli ?: p.caseSizeMilli
        // A price said "per carton" (کارتنی) or with the carton count (no other unit) is a carton price.
        val perCase = item.unitPrice?.takeIf { (item.priceUnit ?: ProductUnit.CARTON) == ProductUnit.CARTON }
        val perPiece = item.unitPrice?.takeIf { item.priceUnit != null && item.priceUnit != ProductUnit.CARTON && item.priceUnit == p.unit }
        val casePrice = perCase ?: item.totalPrice?.let { Money.divRoundHalfUp(it * Money.MILLI, cases) }
        if (size == null) {
            return ResolvedLine(item, p, emptyList(), cases, null, notes, casesMilli = cases, casePrice = casePrice)
        }
        val pieces = Money.divRoundHalfUp(cases * size, Money.MILLI)
        val unitPrice = perPiece ?: casePrice?.let { Money.divRoundHalfUp(it * Money.MILLI, size) }
        if (unitPrice == null) {
            val def = if (forPurchase) p.lastPurchasePrice else p.salePrice
            if (def == 0L) notes += "قیمت گفته نشد"
        }
        return ResolvedLine(
            item, p, emptyList(), pieces, unitPrice, notes,
            casesMilli = cases, caseSizeMilli = size, casePrice = casePrice ?: perPiece?.let { Money.lineTotal(it, size) },
        )
    }

    private fun factor(u: ProductUnit): Long? = when (u) {
        ProductUnit.KILOGRAM -> 1_000L
        ProductUnit.GRAM -> 1L
        else -> null
    }

    /** Quantity conversion for weight units only (others need per-product pack sizes). */
    private fun convert(qtyMilli: Long, from: ProductUnit, to: ProductUnit): Long? {
        val f = factor(from) ?: return null
        val t = factor(to) ?: return null
        return Money.divRoundHalfUp(qtyMilli * f, t)
    }

    /** Price per [from] unit → price per [to] unit (e.g. per-kg → per-gram). */
    private fun convertPrice(price: Long, from: ProductUnit, to: ProductUnit): Long? {
        val f = factor(from) ?: return null
        val t = factor(to) ?: return null
        return Money.divRoundHalfUp(price * t, f)
    }
}
