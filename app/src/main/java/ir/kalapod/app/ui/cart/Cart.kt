package ir.kalapod.app.ui.cart

import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.domain.finance.Fifo
import ir.kalapod.app.domain.finance.LineInput
import ir.kalapod.app.domain.finance.Money

/**
 * Editable cart line shared by purchase and sale screens (and later voice/barcode entry).
 * Fields are kept as typed text so the user can edit freely; parsing happens on demand.
 */
data class CartLine(
    val productId: Long,
    val name: String,
    val unit: ProductUnit,
    val quantityText: String,
    val priceText: String,
    /** Current stock, for oversell warnings on sales. */
    val stockMilli: Long,
    /** Sales: the product's batches in stock, oldest first, each with its own price. */
    val batches: List<Fifo.Batch> = emptyList(),
    /** Sales: the price text the app filled in; while unchanged, each batch is charged at its own price. */
    val autoPriceText: String? = null,
    /** Purchases: sale price of the batch this line brings in (blank = keep the current one). */
    val salePriceText: String = "",
    /**
     * Purchases by the carton: pieces per carton (milli). Then [quantityText] counts cartons and [priceText]
     * is the price of one carton; stock still receives pieces.
     */
    val caseSizeMilli: Long? = null,
    /** The product's known carton size, so the line can be switched to cartons. */
    val knownCaseSizeMilli: Long? = null,
) {
    val byCase get() = caseSizeMilli != null && caseSizeMilli > 0
    /** Pieces that reach stock. */
    fun pieces(): Long? = quantityMilli()?.let { q -> if (byCase) Money.divRoundHalfUp(q * caseSizeMilli!!, Money.MILLI) else q }

    /** Sale price runs this line is charged at (one run unless it spans batches with different prices). */
    fun tiers(currency: Currency): List<Fifo.PriceTier>? {
        val q = quantityMilli() ?: return null
        val p = unitPrice(currency) ?: return null
        if (batches.isEmpty() || priceText != autoPriceText) return listOf(Fifo.PriceTier(q, p))
        return Fifo.priceTiers(batches, q, p)
    }
    /** Sales: one input per price run. */
    fun toInputs(currency: Currency): List<LineInput>? =
        tiers(currency)?.map { LineInput(productId, it.quantityMilli, it.price) }

    fun quantityMilli(): Long? = InputParser.quantity(quantityText)?.takeIf { it > 0 }
    fun unitPrice(currency: Currency): Long? = InputParser.money(priceText, currency)
    fun lineTotal(currency: Currency): Long? =
        if (byCase) toInput(currency)?.lineTotal else tiers(currency)?.sumOf { Money.lineTotal(it.price, it.quantityMilli) }
    fun toInput(currency: Currency): LineInput? {
        val q = quantityMilli() ?: return null
        val p = unitPrice(currency) ?: return null
        val sale = if (salePriceText.isBlank()) null else InputParser.money(salePriceText, currency)
        if (byCase) {
            val size = caseSizeMilli!!
            return LineInput(
                productId, Money.divRoundHalfUp(q * size, Money.MILLI), Money.divRoundHalfUp(p * Money.MILLI, size), sale,
                caseSizeMilli = size, casePrice = p,
            )
        }
        return LineInput(productId, q, p, sale)
    }

    companion object {
        fun of(
            p: ProductEntity, priceRial: Long, currency: Currency, quantityMilli: Long = Money.MILLI,
            batches: List<Fifo.Batch> = emptyList(),
        ): CartLine {
            val price = if (priceRial > 0) InputParser.moneyForEdit(priceRial, currency) else ""
            return CartLine(
                productId = p.id, name = p.name, unit = p.unit,
                quantityText = InputParser.quantityForEdit(quantityMilli),
                priceText = price, stockMilli = p.stockMilli, batches = batches,
                // Batch prices apply only when the price shown is the shelf price (not typed or spoken).
                autoPriceText = price.takeIf { batches.isNotEmpty() && priceRial == p.salePrice },
                knownCaseSizeMilli = p.caseSizeMilli,
            )
        }
    }
}

/** Purchases: prefills the batch's sale price (the given one, or the product's current price). */
fun CartLine.withSalePrice(p: ProductEntity, currency: Currency, price: Long? = null): CartLine {
    val v = price ?: p.salePrice
    return copy(salePriceText = if (v > 0) InputParser.moneyForEdit(v, currency) else "")
}

/** Purchases: the same line counted in cartons of [size] pieces, at [casePrice] per carton. */
fun CartLine.inCases(cases: Long, size: Long, casePrice: Long?, currency: Currency): CartLine = copy(
    caseSizeMilli = size, quantityText = InputParser.quantityForEdit(cases),
    priceText = casePrice?.takeIf { it > 0 }?.let { InputParser.moneyForEdit(it, currency) } ?: "",
)

/** Switches a purchase line between pieces and cartons, keeping the same amount of goods and money. */
fun CartLine.toggleCases(currency: Currency): CartLine {
    val size = knownCaseSizeMilli ?: return this
    val q = quantityMilli() ?: Money.MILLI
    val p = unitPrice(currency)
    return if (byCase) copy(
        caseSizeMilli = null, quantityText = InputParser.quantityForEdit(Money.divRoundHalfUp(q * size, Money.MILLI)),
        priceText = p?.let { InputParser.moneyForEdit(Money.divRoundHalfUp(it * Money.MILLI, size), currency) } ?: "",
    ) else inCases(maxOf(Money.MILLI, Money.divRoundHalfUp(q * Money.MILLI, size)), size, p?.let { Money.lineTotal(it, size) }, currency)
}

/** How a document is settled. CREDIT = some or all of it stays as a debt (نسیه). */
enum class Settlement(val label: String) { CASH("نقد"), CARD("کارت"), CREDIT("نسیه") }

/** Adds a product, or bumps its quantity by one unit if it's already in the cart. */
fun List<CartLine>.addOrIncrement(line: CartLine): List<CartLine> {
    val i = indexOfFirst { it.productId == line.productId && it.caseSizeMilli == line.caseSizeMilli }
    if (i < 0) return this + line
    val existing = this[i]
    val q = (existing.quantityMilli() ?: 0L) + (line.quantityMilli() ?: Money.MILLI)
    return toMutableList().also { it[i] = existing.copy(quantityText = InputParser.quantityForEdit(q)) }
}
