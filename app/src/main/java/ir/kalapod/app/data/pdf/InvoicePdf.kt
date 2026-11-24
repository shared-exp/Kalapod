package ir.kalapod.app.data.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import ir.kalapod.app.R
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import java.io.File

/** Everything printed on an invoice; already formatted-agnostic (money in rial, qty in milli). */
data class InvoiceData(
    val shopName: String,
    val title: String,              // "فاکتور فروش"
    val number: String,
    val dateText: String,           // already Jalali + Persian digits
    val partyLabel: String?,        // "مشتری" / "تأمین‌کننده"
    val partyName: String?,
    val lines: List<Line>,
    val subtotal: Long,
    val discount: Long,
    val extraCost: Long,
    val total: Long,
    val paid: Long,
    val reversed: Boolean,
    val currency: Currency,
) {
    /** [caseSizeMilli]: bought by the carton — quantity shown in cartons, price per carton. */
    data class Line(
        val name: String, val quantityMilli: Long, val unitLabel: String, val unitPrice: Long, val lineTotal: Long,
        val caseSizeMilli: Long? = null,
    )
}

/**
 * Renders a Persian, right-to-left A4 invoice with Android's PdfDocument. Text goes through
 * StaticLayout so Arabic-script shaping and bidi are handled by the platform.
 */
object InvoicePdf {
    private const val W = 595   // A4 in points
    private const val H = 842
    private const val M = 36f   // margin
    private const val ROW_H = 26f

    fun write(context: Context, data: InvoiceData, out: File): File {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 10f; color = Color.BLACK }
        val strong = TextPaint(text).apply { typeface = bold }
        val muted = TextPaint(text).apply { color = Color.rgb(90, 90, 80) }
        val line = Paint().apply { color = Color.rgb(200, 196, 184); strokeWidth = 0.7f }
        val fill = Paint().apply { color = Color.rgb(237, 232, 218) }
        val money = { v: Long -> PersianFormat.money(v, data.currency, withUnit = false) }

        // Columns from the right edge (RTL): row #, item, qty, unit price, total.
        val right = W - M
        val cols = floatArrayOf(40f, 193f, 80f, 100f, 110f) // widths, right → left
        val colRight = FloatArray(cols.size).also { var x = right; for (i in cols.indices) { it[i] = x; x -= cols[i] } }

        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f

        fun header() {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page!!.canvas
            y = M
            draw(c!!, data.shopName.ifBlank { "فروشگاه" }, strong.apply { textSize = 16f }, right, y, W - 2 * M)
            strong.textSize = 10f
            y += 26f
            draw(c!!, data.title + if (data.reversed) " — باطل‌شده" else "", strong.apply { textSize = 13f }, right, y, 300f)
            strong.textSize = 10f
            draw(c!!, "شماره: " + PersianFormat.ltr(data.number), text, M + 200f, y, 200f)
            y += 18f
            draw(c!!, "تاریخ: " + data.dateText, text, M + 200f, y, 200f)
            if (data.partyName != null) draw(c!!, "${data.partyLabel}: ${data.partyName}", text, right, y, 300f)
            y += 26f
            // Table header
            c!!.drawRect(M, y, right, y + ROW_H, fill)
            listOf("ردیف", "شرح کالا", "مقدار", "فی (${data.currency.label})", "مبلغ (${data.currency.label})").forEachIndexed { i, h ->
                draw(c!!, h, strong, colRight[i] - 4f, y + 7f, cols[i] - 8f)
            }
            y += ROW_H
        }

        header()
        data.lines.forEachIndexed { i, l ->
            if (y + ROW_H > H - M - 30f) { doc.finishPage(page); header() }
            val cells = listOf(
                PersianFormat.number((i + 1).toLong()),
                l.name,
                PersianFormat.withCases(l.quantityMilli, l.unitLabel, l.caseSizeMilli),
                money(perCase(l) ?: l.unitPrice),
                money(l.lineTotal),
            )
            cells.forEachIndexed { k, s -> draw(c!!, s, text, colRight[k] - 4f, y + 7f, cols[k] - 8f, maxLines = 1) }
            c!!.drawLine(M, y + ROW_H, right, y + ROW_H, line)
            y += ROW_H
        }

        // Totals block (left side under the table); move to a fresh page if it doesn't fit.
        if (y + 140f > H - M - 20f) { doc.finishPage(page); header() }
        y += 12f
        val labelX = M + 200f
        fun total(label: String, v: Long, paint: TextPaint = text) {
            draw(c!!, label, paint, labelX, y, 120f)
            draw(c!!, money(v) + " " + data.currency.label, paint, M + 80f, y, 120f)
            y += 18f
        }
        total("جمع اقلام", data.subtotal)
        if (data.discount > 0) total("تخفیف", data.discount)
        if (data.extraCost > 0) total("هزینه جانبی", data.extraCost)
        total("مبلغ کل", data.total, strong)
        if (data.paid != data.total) {
            total("پرداخت‌شده", data.paid)
            total("مانده", data.total - data.paid, strong)
        }
        draw(c!!, "صادر شده با کالاپاد", muted, right, H - M - 10f, 200f)
        doc.finishPage(page)

        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Draws RTL text whose right edge is at [rightX], wrapping in [width]. */
    private fun draw(c: Canvas, s: String, p: TextPaint, rightX: Float, top: Float, width: Float, maxLines: Int = 2) {
        val w = width.toInt().coerceAtLeast(10)
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(rightX - w, top)
        layout.draw(c)
        c.restore()
    }
}

/** Price of one carton for a line bought by the carton (exact from the line total). */
internal fun perCase(l: InvoiceData.Line): Long? {
    val size = l.caseSizeMilli ?: return null
    if (l.quantityMilli <= 0) return null
    return ir.kalapod.app.domain.finance.Money.divRoundHalfUp(l.lineTotal * size, l.quantityMilli)
}
