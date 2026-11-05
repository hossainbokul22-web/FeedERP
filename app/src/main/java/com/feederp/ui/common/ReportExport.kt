package com.feederp.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.feederp.ui.theme.AppLang
import com.feederp.ui.theme.LocalLang
import com.feederp.ui.theme.Translator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** একটি রিপোর্টের সারি; bold = মোট/শিরোনাম সারি। */
data class ReportRow(val cells: List<String>, val bold: Boolean = false)

/** রিপোর্টের একটি অংশ (টেবিল)। rightCols = ডানে সাজানো কলামের সূচক (সংখ্যার কলাম)। */
data class ReportSection(
    val heading: String?,
    val headers: List<String>,
    val rows: List<ReportRow>,
    val rightCols: Set<Int> = emptySet(),
)

data class ReportTable(val title: String, val subtitle: String?, val sections: List<ReportSection>)

/** কোম্পানির নাম (রিপোর্ট/ইনভয়েসের মাথায়)। সংরক্ষণ: অ্যাপের ব্যক্তিগত SharedPreferences। */
object CompanyProfile {
    private const val FILE = "company"
    fun name(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("name", null)?.takeIf { it.isNotBlank() } ?: "Feed ERP"

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("name", name.trim()).apply()
    }
}

/**
 * রিপোর্ট PDF (A4, PdfDocument)। একাধিক অংশ, পাতা ভরলে নতুন পাতা ও টেবিল-হেডার আবার। প্রতি পাতায় কোম্পানির নাম, রিপোর্টের নাম, প্রস্তুতের সময় ও পাতা নম্বর।
 * সীমা: কম্পাইল ও ডিভাইসে পরীক্ষা হয়নি; বাংলা সিস্টেম ফন্টে আঁকা হয়; লম্বা লেখা "…" দিয়ে কাটা।
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f
    private const val ROW = 17f

    fun write(out: OutputStream, company: String, t: ReportTable) {
        val doc = PdfDocument()
        val txt = Paint().apply { textSize = 9.5f; isAntiAlias = true; typeface = Typeface.DEFAULT }
        val txtBold = Paint(txt).apply { typeface = Typeface.DEFAULT_BOLD }
        val txtR = Paint(txt).apply { textAlign = Paint.Align.RIGHT }
        val txtRBold = Paint(txtBold).apply { textAlign = Paint.Align.RIGHT }
        val big = Paint().apply { textSize = 15f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val mid = Paint().apply { textSize = 12f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val small = Paint().apply { textSize = 8.5f; isAntiAlias = true; color = 0xFF555555.toInt() }
        val shade = Paint().apply { color = 0xFFE6EFEB.toInt() }
        val zebra = Paint().apply { color = 0xFFF5F8F7.toInt() }
        val line = Paint().apply { color = 0xFF999999.toInt(); strokeWidth = 0.6f }
        val printedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun cut(p: Paint, s: String, maxW: Float): String {
            val n = p.breakText(s, true, maxW, null)
            return if (n >= s.length) s else s.substring(0, (n - 1).coerceAtLeast(0)) + "…"
        }
        fun finish() {
            page?.let {
                it.canvas.drawText("Page $pageNo", W / 2f - 18f, H - 20f, small)
                it.canvas.drawText("Feed ERP", M, H - 20f, small)
                doc.finishPage(it)
            }
            page = null
        }
        fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            val c = page!!.canvas
            y = M + 6
            c.drawText(company, M, y + 10, big); y += 20
            c.drawText(t.title, M, y + 8, mid); y += 16
            t.subtitle?.let { c.drawText(cut(small, it, W - 2 * M), M, y + 6, small); y += 12 }
            c.drawText("Printed: $printedAt", M, y + 6, small); y += 10
            c.drawLine(M, y, W - M, y, line); y += 8
        }

        newPage()
        for (sec in t.sections) {
            val cols = sec.headers.size.coerceAtLeast(1)
            val tableW = W - 2 * M
            val firstW = if (cols <= 2) tableW * 0.7f else tableW * 0.30f
            val otherW = if (cols > 1) (tableW - firstW) / (cols - 1) else 0f
            val xs = FloatArray(cols + 1)
            xs[0] = M
            for (i in 1..cols) xs[i] = xs[i - 1] + (if (i == 1) firstW else otherW)

            fun drawHeaderRow() {
                val c = page!!.canvas
                c.drawRect(M, y, W - M, y + ROW, shade)
                sec.headers.forEachIndexed { i, h ->
                    val w = xs[i + 1] - xs[i] - 6f
                    if (i in sec.rightCols) c.drawText(cut(txtBold, h, w), xs[i + 1] - 3f, y + 12f, txtRBold)
                    else c.drawText(cut(txtBold, h, w), xs[i] + 3f, y + 12f, txtBold)
                }
                y += ROW
            }

            if (y > H - 90) newPage()
            sec.heading?.let { page!!.canvas.drawText(it, M, y + 10f, mid); y += 18f }
            drawHeaderRow()
            sec.rows.forEachIndexed { idx, r ->
                if (y > H - 50) { newPage(); drawHeaderRow() }
                val c = page!!.canvas
                if (idx % 2 == 1) c.drawRect(M, y, W - M, y + ROW, zebra)
                r.cells.forEachIndexed { i, cell ->
                    if (i >= cols) return@forEachIndexed
                    val w = xs[i + 1] - xs[i] - 6f
                    val right = i in sec.rightCols
                    val p = if (right) (if (r.bold) txtRBold else txtR) else (if (r.bold) txtBold else txt)
                    if (right) c.drawText(cut(p, cell, w), xs[i + 1] - 3f, y + 12f, p)
                    else c.drawText(cut(p, cell, w), xs[i] + 3f, y + 12f, p)
                }
                y += ROW
            }
            page!!.canvas.drawLine(M, y, W - M, y, line)
            y += 14f
        }
        finish()
        doc.writeTo(out)
        doc.close()
    }

    fun bytes(company: String, t: ReportTable): ByteArray {
        val bos = ByteArrayOutputStream()
        write(bos, company, t)
        return bos.toByteArray()
    }
}

private class PdfPrintAdapter(private val data: ByteArray, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),
            true,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileOutputStream(destination.fileDescriptor).use { it.write(data) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

private fun translateTable(t: ReportTable): ReportTable {
    fun s(x: String) = Translator.en(x)
    return ReportTable(
        s(t.title), t.subtitle?.let(::s),
        t.sections.map { sec ->
            ReportSection(sec.heading?.let(::s), sec.headers.map(::s), sec.rows.map { r -> ReportRow(r.cells.map(::s), r.bold) }, sec.rightCols)
        },
    )
}

private fun safeName(title: String): String {
    val base = title.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "report" }
    return base + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(Date())
}

/** টপ-বারে তিন বোতাম: PDF সংরক্ষণ, শেয়ার (WhatsApp/ইমেইল ইত্যাদি), প্রিন্ট। table = null হলে কিছু দেখায় না। */
@Composable
fun ReportActions(table: ReportTable?) {
    if (table == null) return
    val context = LocalContext.current
    val en = LocalLang.current == AppLang.EN
    val shown = remember(table, en) { if (en) translateTable(table) else table }
    val company = remember { CompanyProfile.name(context) }
    val name = remember(shown) { safeName(table.title.ifBlank { shown.title }) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { ReportPdf.write(it, company, shown) }
            }
        }
    }

    IconButton(onClick = { saver.launch("$name.pdf") }) {
        Icon(Icons.Filled.PictureAsPdf, contentDescription = "PDF")
    }
    IconButton(onClick = {
        runCatching {
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val f = File(dir, "$name.pdf")
            FileOutputStream(f).use { ReportPdf.write(it, company, shown) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, shown.title))
        }
    }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
    IconButton(onClick = {
        runCatching {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(name, PdfPrintAdapter(ReportPdf.bytes(company, shown), name), null)
        }
    }) { Icon(Icons.Filled.Print, contentDescription = "Print") }
}
