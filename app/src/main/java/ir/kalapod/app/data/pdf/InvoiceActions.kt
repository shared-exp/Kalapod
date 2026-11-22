package ir.kalapod.app.data.pdf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File

/** Share or print a generated invoice PDF. */
object InvoiceActions {
    fun share(context: Context, pdf: File, title: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", pdf)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "ارسال فاکتور").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun print(context: Context, pdf: File, jobName: String) {
        val pm = context.getSystemService(PrintManager::class.java) ?: return
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
                cb: LayoutResultCallback, extras: Bundle?,
            ) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(
                    PrintDocumentInfo.Builder(pdf.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true,
                )
            }

            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                runCatching {
                    pdf.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { input.copyTo(it) } }
                }.onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                    .onFailure { cb.onWriteFailed(it.message) }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}
