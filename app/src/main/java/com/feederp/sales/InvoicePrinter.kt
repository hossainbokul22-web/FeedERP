package com.feederp.sales

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrintLine(val name: String, val qtyKg: String, val rate: String, val amount: String)

data class InvoicePrintModel(
    val companyName: String,
    val invoiceNo: String,
    val dateMillis: Long,
    val partyName: String,
    val lines: List<PrintLine>,
    val subtotal: String,
    val discount: String,
    val total: String,
    val paid: String,
    val returned: String?,
    val due: String,
    val note: String?,
)

/**
 * ইনভয়েস PDF (A4, android.graphics.pdf.PdfDocument)। বাংলা লেখা সিস্টেম ফন্টে আঁকা হয় (Android 8+ এ বাংলা শেপিং আছে);
 * সংখ্যা ইংরেজি অঙ্কে। লাইন বেশি হলে একাধিক পাতা। কোম্পানির নাম/ঠিকানা এখনও সেটিংসে নেই — [InvoicePrintModel.companyName] দিতে হয়।
 * কম্পাইল ও ডিভাইসে পরীক্ষা হয়নি (বিশেষ করে বাংলা ফন্ট রেন্ডারিং)।
 */
object InvoicePrinter {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    fun write(out: OutputStream, m: InvoicePrintModel) {
        val doc = PdfDocument()
        val normal = Paint().apply { textSize = 11f; isAntiAlias = true; typeface = Typeface.DEFAULT }
        val bold = Paint().apply { textSize = 11f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val title = Paint().apply { textSize = 18f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val right = Paint(normal).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(bold).apply { textAlign = Paint.Align.RIGHT }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M + 10
        }
        fun cut(p: Paint, s: String, maxW: Float): String {
            val n = p.breakText(s, true, maxW, null)
            return if (n >= s.length) s else s.substring(0, (n - 1).coerceAtLeast(0)) + "…"
        }
        fun header() {
            val c = page!!.canvas
            c.drawText(m.companyName, M, y, title); y += 24
            c.drawText("ইনভয়েস: ${m.invoiceNo}", M, y, bold)
            right.also { c.drawText(SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(m.dateMillis)), W - M, y, it) }; y += 16
            c.drawText("ক্রেতা: ${m.partyName}", M, y, normal); y += 22
            c.drawText("আইটেম", M, y, bold)
            c.drawText("কেজি", 330f, y, rightBold); c.drawText("দর/কেজি", 430f, y, rightBold); c.drawText("টাকা", W - M, y, rightBold)
            y += 6; c.drawLine(M, y, W - M, y, normal); y += 14
        }

        newPage(); header()
        for (l in m.lines) {
            if (y > H - 170) { newPage(); header() }
            val c = page!!.canvas
            c.drawText(cut(normal, l.name, 250f), M, y, normal)
            c.drawText(l.qtyKg, 330f, y, right); c.drawText(l.rate, 430f, y, right); c.drawText(l.amount, W - M, y, right)
            y += 16
        }
        if (y > H - 150) { newPage(); header() }
        val c = page!!.canvas
        y += 4; c.drawLine(M, y, W - M, y, normal); y += 16
        fun total(label: String, v: String, b: Boolean = false) {
            c.drawText(label, 380f, y, if (b) bold else normal); c.drawText(v, W - M, y, if (b) rightBold else right); y += 16
        }
        total("সাবটোটাল", m.subtotal); total("ডিসকাউন্ট", m.discount); total("মোট", m.total, true)
        total("আদায়", m.paid); m.returned?.let { total("ফেরত (ক্রেডিট)", it) }; total("বকেয়া", m.due, true)
        m.note?.let { y += 6; c.drawText(cut(normal, "নোট: $it", W - 2 * M), M, y, normal) }
        page?.let { doc.finishPage(it) }
        try { doc.writeTo(out); out.flush() } finally { doc.close() }
    }
}
