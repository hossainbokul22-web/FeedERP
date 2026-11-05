package com.feederp.ui.theme

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * বাংলা → ইংরেজি রানটাইম অনুবাদ। assets/en.tsv (বাংলা<TAB>English) থেকে লোড হয়।
 * পুরো লেখা মিললে সরাসরি; নইলে (যেমন "বাকি: ৳১২০") ছোট টুকরো শব্দ-সীমা মেনে বদলানো হয়। অজানা লেখা (ইউজারের টাইপ করা নাম ইত্যাদি) অপরিবর্তিত থাকে।
 */
object Translator {
    private var exact: Map<String, String> = emptyMap()
    private var frags: List<Pair<String, String>> = emptyList()
    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        try {
            val map = HashMap<String, String>()
            context.assets.open("en.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val i = line.indexOf('\t')
                    if (i > 0) map[line.substring(0, i).replace("\\n", "\n")] = line.substring(i + 1).replace("\\n", "\n")
                }
            }
            exact = map
            frags = map.entries.sortedByDescending { it.key.length }.map { it.key to it.value }
        } catch (_: Exception) {
            // ফাইল না পেলে অনুবাদ ছাড়াই (বাংলায়) চলবে
        }
        loaded = true
    }

    private fun isBnLetter(c: Char) = c in 'ঀ'..'৿' && c !in '০'..'৯'
    private fun hasBengali(s: String) = s.any { it in 'ঀ'..'৿' }

    fun en(text: String): String {
        if (text.isEmpty() || !hasBengali(text)) return text
        cache[text]?.let { return it }
        val r = compute(text)
        if (cache.size > 4000) cache.clear()
        cache[text] = r
        return r
    }

    private fun compute(text: String): String {
        val trimmed = text.trim()
        exact[trimmed]?.let { hit ->
            val lead = text.substring(0, text.indexOf(trimmed))
            val trail = text.substring(lead.length + trimmed.length)
            return toAsciiDigits(lead + hit + trail)
        }
        var s = text
        for ((k, v) in frags) {
            var from = 0
            while (true) {
                val i = s.indexOf(k, from)
                if (i < 0) break
                val end = i + k.length
                val okBefore = !(i > 0 && isBnLetter(s[i - 1]) && isBnLetter(k.first()))
                val okAfter = !(end < s.length && isBnLetter(s[end]) && isBnLetter(k.last()))
                if (okBefore && okAfter) {
                    s = s.substring(0, i) + v + s.substring(end)
                    from = i + v.length
                } else {
                    from = i + 1
                }
            }
        }
        return toAsciiDigits(s)
    }

    private fun toAsciiDigits(s: String): String {
        if (s.none { it in '০'..'৯' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '০'..'৯') ('0' + (c - '০')) else c)
        return sb.toString()
    }
}
