package ir.kalapod.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.data.pdf.InvoiceData
import ir.kalapod.app.data.pdf.InvoicePdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InvoicePdfTest {
    @Test fun writesMultiPagePersianInvoice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = (1..40).map {
            InvoiceData.Line(if (it % 2 == 0) "برنج هاشمی درجه یک" else "روغن لادن ۱.۵ لیتری", 2_500, "کیلوگرم", 1_800_000, 4_500_000)
        }
        val data = InvoiceData(
            shopName = "سوپرمارکت نمونه", title = "فاکتور فروش", number = "S-1405-000012", dateText = "۱۴۰۵/۰۸/۳۰ ۱۰:۲۵",
            partyLabel = "مشتری", partyName = "آقای رضایی", lines = lines, subtotal = 180_000_000, discount = 500_000,
            extraCost = 0, total = 179_500_000, paid = 100_000_000, reversed = false, currency = Currency.TOMAN,
        )
        val out = InvoicePdf.write(ctx, data, File(ctx.cacheDir, "invoices/test.pdf"))
        assertTrue(out.length() > 1_000)

        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertEquals(2, r.pageCount)
            r.openPage(0).use { page ->
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // Saved for manual visual inspection: adb pull from the app's files dir.
                File(ctx.getExternalFilesDir(null), "invoice-page1.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
