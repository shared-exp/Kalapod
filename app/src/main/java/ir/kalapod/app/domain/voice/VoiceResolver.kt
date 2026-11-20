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
) {
    val needsChoice get() = product == null && options.isNotEmpty()
    val isUnknown get() = product == null && options.isEmpty()
}

/**
 * Maps parsed items to catalog products, converts units where it's unambiguous (g ↔ kg), and fills
 * sensible defaults so the user is asked only what really matters (which product, when unsure).
 */
object VoiceResolver {

    fun resolve(cmd: ParsedCommand, catalog: List<CatalogProduct>, forPurchase: Boolean): List<ResolvedLine> {
        val candidates = catalog.map { ProductMatcher.Candidate(it.id, it.name) }
        val byId = catalog.associateBy { it.id }
        return cmd.items.filter { it.name.isNotBlank() }.map { item ->
            when (val m = ProductMatcher.match(item.name, candidates)) {
                is ProductMatcher.Match.Sure -> fit(item, byId.getValue(m.candidate.id), forPurchase)
                is ProductMatcher.Match.Ambiguous -> ResolvedLine(
                    item, null, m.options.map { byId.getValue(it.id) },
                    item.quantityMilli ?: Money.MILLI, null, listOf("کدام «${item.name}»؟"),
                )
                ProductMatcher.Match.None -> ResolvedLine(
                    item, null, emptyList(), item.quantityMilli ?: Money.MILLI, null,
                    listOf("«${item.name}» در کالاها نیست"),
                )
            }
        }
    }

    /** Re-fits a line after the user picks/defines its product. */
    fun choose(line: ResolvedLine, product: CatalogProduct, forPurchase: Boolean) = fit(line.heard, product, forPurchase)

    private fun fit(item: ParsedItem, p: CatalogProduct, forPurchase: Boolean): ResolvedLine {
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
