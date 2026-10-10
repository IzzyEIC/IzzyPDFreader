package com.pdfreader

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Prosta konwersja .docx → HTML. Obsługuje to, co najczęściej spotyka się w dokumentach:
 * akapity, nagłówki, wyrównanie, pogrubienie/kursywę/podkreślenie, kolory, listy,
 * tabele, obrazki i linki. Nie odtwarza układu stron (nagłówków/stopek, kolumn itp.).
 */
class DocxConverter private constructor(private val parts: Map<String, ByteArray>) {

    private class Level(val format: String, val text: String, val start: Int)
    private class Rel(val target: String, val external: Boolean)

    private class Para(val sb: StringBuilder = StringBuilder()) {
        var style: String? = null
        var align: String? = null
        var numId: String? = null
        var level = 0
    }

    private class Run {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var color: String? = null
        var sizePt: Float? = null
        var highlight: String? = null
        var vertAlign: String? = null
        var closing: String? = null
    }

    private val rels = HashMap<String, Rel>()
    private val styleNames = HashMap<String, String>()
    private val numbering = HashMap<String, Map<Int, Level>>()
    private val counters = HashMap<String, IntArray>()

    private val body = StringBuilder()
    private val paras = ArrayDeque<Para>()
    private val runs = ArrayDeque<Run>()
    private val cellAttrPos = ArrayDeque<Pair<StringBuilder, Int>>()
    private var imageWidthPx: Int? = null

    private fun top(): StringBuilder = paras.lastOrNull()?.sb ?: body

    private fun convert(): String {
        val document = parts["word/document.xml"] ?: throw IllegalArgumentException("Brak word/document.xml")
        parts["word/_rels/document.xml.rels"]?.let(::parseRels)
        parts["word/styles.xml"]?.let(::parseStyles)
        parts["word/numbering.xml"]?.let(::parseNumbering)
        parseDocument(document)
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=yes\">" +
            "<style>$CSS</style></head><body><div class=\"doc\">$body</div></body></html>"
    }

    // ---------- Pliki pomocnicze ----------

    private fun parseRels(bytes: ByteArray) = walk(bytes) { p ->
        if (p.name == "Relationship") {
            val id = p.attr("Id") ?: return@walk
            val target = p.attr("Target") ?: return@walk
            rels[id] = Rel(target, p.attr("TargetMode") == "External")
        }
    }

    private fun parseStyles(bytes: ByteArray) {
        var styleId: String? = null
        walk(bytes) { p ->
            when (p.name) {
                "style" -> styleId = p.attr("styleId")
                "name" -> styleId?.let { id -> p.attr("val")?.let { styleNames[id] = it.lowercase() } }
            }
        }
    }

    private fun parseNumbering(bytes: ByteArray) {
        val abstract = HashMap<String, HashMap<Int, Level>>()
        val numToAbstract = HashMap<String, String>()
        var currentAbstract: HashMap<Int, Level>? = null
        var currentNum: String? = null
        var lvl = 0
        var format = "decimal"
        var text = "%1."
        var start = 1
        walkWithEnd(bytes,
            onStart = { p ->
                when (p.name) {
                    "abstractNum" -> currentAbstract = HashMap<Int, Level>().also {
                        abstract[p.attr("abstractNumId") ?: ""] = it
                    }
                    "lvl" -> {
                        lvl = p.attr("ilvl")?.toIntOrNull() ?: 0
                        format = "decimal"; text = "%${lvl + 1}."; start = 1
                    }
                    "numFmt" -> format = p.attr("val") ?: format
                    "lvlText" -> text = p.attr("val") ?: text
                    "start" -> start = p.attr("val")?.toIntOrNull() ?: 1
                    "num" -> currentNum = p.attr("numId")
                    "abstractNumId" -> currentNum?.let { n -> p.attr("val")?.let { numToAbstract[n] = it } }
                }
            },
            onEnd = { p ->
                when (p.name) {
                    "lvl" -> currentAbstract?.put(lvl, Level(format, text, start))
                    "abstractNum" -> currentAbstract = null
                    "num" -> currentNum = null
                }
            })
        numToAbstract.forEach { (num, abs) -> abstract[abs]?.let { numbering[num] = it } }
    }

    // ---------- Treść dokumentu ----------

    private fun parseDocument(bytes: ByteArray) {
        val p = newParser(bytes)
        val path = ArrayList<String>()
        var skipDepth = 0
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> {
                    if (skipDepth > 0) { skipDepth++; continue }
                    // AlternateContent ma wersję główną (Choice) i zapasową (Fallback) — bierzemy tylko jedną.
                    if (p.name == "Fallback") { skipDepth = 1; continue }
                    onStart(p, path.lastOrNull(), path.getOrNull(path.size - 2))
                    path += p.name
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) { skipDepth--; continue }
                    path.removeAt(path.size - 1)
                    onEnd(p.name)
                }
                XmlPullParser.TEXT -> {
                    if (skipDepth == 0 && path.lastOrNull() == "t" && path.getOrNull(path.size - 2) == "r") {
                        appendRun(escape(p.text))
                    }
                }
            }
        }
    }

    private fun onStart(p: XmlPullParser, parent: String?, grandparent: String?) {
        val para = paras.lastOrNull()
        val run = runs.lastOrNull()
        when (p.name) {
            "p" -> paras.addLast(Para())
            "pStyle" -> if (parent == "pPr") para?.style = p.attr("val")
            "jc" -> if (parent == "pPr") para?.align = p.attr("val")
            "numId" -> if (parent == "numPr") para?.numId = p.attr("val")
            "ilvl" -> if (parent == "numPr") para?.level = p.attr("val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            "r" -> runs.addLast(Run())
            "tab" -> if (parent == "r") appendRun("&emsp;&emsp;")
            "br" -> if (parent == "r") appendRun(if (p.attr("type") == "page") "<hr class=\"pb\">" else "<br>")
            "cr" -> if (parent == "r") appendRun("<br>")
            "noBreakHyphen" -> if (parent == "r") appendRun("&#8209;")
            "hyperlink" -> {
                // Tylko linki zewnętrzne (http/https/mailto); odnośniki wewnątrz dokumentu pomijamy.
                val href = p.attr("id")?.let { rels[it] }
                    ?.takeIf { it.external && SAFE_LINK.containsMatchIn(it.target) }?.target
                top().append(if (href != null) "<a href=\"${escape(href)}\">" else "<a>")
            }
            "tbl" -> top().append("<table>")
            "tr" -> top().append("<tr>")
            "tc" -> {
                val sb = top()
                sb.append("<td")
                cellAttrPos.addLast(sb to sb.length)
                sb.append(">")
            }
            "gridSpan" -> if (parent == "tcPr") {
                val span = p.attr("val")?.toIntOrNull() ?: 1
                cellAttrPos.lastOrNull()?.let { (sb, pos) -> if (span > 1) sb.insert(pos, " colspan=\"$span\"") }
            }
            "extent" -> imageWidthPx = p.attr("cx")?.toLongOrNull()?.let { (it / EMU_PER_PX).toInt() }
            "blip" -> p.attr("embed")?.let { appendImage(it) }
            "imagedata" -> p.attr("id")?.let { appendImage(it) }
        }
        if (run != null && parent == "rPr" && grandparent == "r") {
            when (p.name) {
                "b" -> run.bold = p.isOn()
                "i" -> run.italic = p.isOn()
                "u" -> run.underline = p.attr("val") != "none"
                "strike", "dstrike" -> run.strike = p.isOn()
                "color" -> run.color = p.attr("val")?.takeIf { it != "auto" && it.matches(HEX) }
                "sz" -> run.sizePt = p.attr("val")?.toFloatOrNull()?.div(2)
                "highlight" -> run.highlight = p.attr("val")?.takeIf { it != "none" && it.matches(WORD) }
                "vertAlign" -> run.vertAlign = p.attr("val")
            }
        }
    }

    private fun onEnd(name: String) {
        when (name) {
            "p" -> paras.removeLastOrNull()?.let { top().append(renderParagraph(it)) }
            "r" -> runs.removeLastOrNull()?.closing?.let { top().append(it) }
            "hyperlink" -> top().append("</a>")
            "tbl" -> top().append("</table>")
            "tr" -> top().append("</tr>")
            "tc" -> {
                cellAttrPos.removeLastOrNull()
                top().append("</td>")
            }
            "drawing", "pict" -> imageWidthPx = null
        }
    }

    private fun appendRun(html: String) {
        val run = runs.lastOrNull()
        if (run != null && run.closing == null) {
            val open = StringBuilder()
            val close = StringBuilder()
            val css = buildList {
                run.color?.let { add("color:#$it") }
                run.sizePt?.let { add("font-size:${it}pt") }
                run.highlight?.let { add("background:$it") }
            }
            if (css.isNotEmpty()) { open.append("<span style=\"${css.joinToString(";")}\">"); close.insert(0, "</span>") }
            fun tag(on: Boolean, t: String) { if (on) { open.append("<$t>"); close.insert(0, "</$t>") } }
            tag(run.bold, "b")
            tag(run.italic, "i")
            tag(run.underline, "u")
            tag(run.strike, "s")
            tag(run.vertAlign == "superscript", "sup")
            tag(run.vertAlign == "subscript", "sub")
            top().append(open)
            run.closing = close.toString()
        }
        top().append(html)
    }

    private fun appendImage(relId: String) {
        val rel = rels[relId] ?: return
        if (rel.external) return
        val path = resolve(rel.target)
        val bytes = parts[path] ?: return
        val mime = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> return // np. EMF/WMF — przeglądarka ich nie wyświetli
        }
        val width = imageWidthPx?.let { " style=\"width:${it}px\"" } ?: ""
        appendRun("<img src=\"data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"$width>")
    }

    private fun renderParagraph(para: Para): String {
        val styleName = para.style?.let { styleNames[it] ?: it.lowercase() } ?: ""
        val heading = HEADING.find(styleName)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6)
            ?: when (styleName) {
                "title" -> 1
                "subtitle" -> 2
                else -> null
            }
        val tag = heading?.let { "h$it" } ?: "p"
        val css = ArrayList<String>()
        when (para.align) {
            "center" -> css += "text-align:center"
            "right", "end" -> css += "text-align:right"
            "both", "distribute" -> css += "text-align:justify"
        }
        var content = para.sb.toString()
        val numId = para.numId
        if (numId != null && numId != "0") {
            css += "margin-left:${1.5 * (para.level + 1)}em"
            css += "text-indent:-1.5em"
            content = "<span class=\"m\">${escape(listMarker(numId, para.level))}</span>$content"
        }
        if (content.isEmpty()) content = "&nbsp;"
        val style = if (css.isEmpty()) "" else " style=\"${css.joinToString(";")}\""
        return "<$tag$style>$content</$tag>"
    }

    private fun listMarker(numId: String, level: Int): String {
        val levels = numbering[numId] ?: return "•"
        val count = counters.getOrPut(numId) { IntArray(9) }
        count[level]++
        for (deeper in level + 1 until count.size) count[deeper] = 0
        val lvl = levels[level] ?: return "•"
        return when (lvl.format) {
            "bullet" -> BULLETS[level % BULLETS.size]
            "none" -> ""
            else -> PLACEHOLDER.replace(lvl.text) { m ->
                val k = m.groupValues[1].toInt() - 1
                val l = levels[k] ?: return@replace ""
                formatNumber(maxOf(count.getOrElse(k) { 0 }, 1) + l.start - 1, l.format)
            }
        }
    }

    private fun resolve(target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = "word/$target".split("/").fold(ArrayList<String>()) { acc, s ->
            when (s) {
                ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.size - 1)
                ".", "" -> {}
                else -> acc += s
            }
            acc
        }
        return parts.joinToString("/")
    }

    // ---------- XML ----------

    private fun newParser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(bytes.inputStream(), null)
    }

    private fun walk(bytes: ByteArray, onStart: (XmlPullParser) -> Unit) = walkWithEnd(bytes, onStart) {}

    private fun walkWithEnd(bytes: ByteArray, onStart: (XmlPullParser) -> Unit, onEnd: (XmlPullParser) -> Unit) {
        val p = newParser(bytes)
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> onStart(p)
                XmlPullParser.END_TAG -> onEnd(p)
            }
        }
    }

    /** Atrybut po nazwie lokalnej, bez względu na przestrzeń nazw (w:val, r:id itd.). */
    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.isOn() = attr("val").let { it == null || (it != "0" && it != "false" && it != "off") }

    companion object {
        private const val EMU_PER_PX = 9525L
        private const val MAX_PART_BYTES = 30L * 1024 * 1024
        private val HEADING = Regex("""^heading (\d)""")
        private val PLACEHOLDER = Regex("""%(\d)""")
        private val HEX = Regex("""[0-9A-Fa-f]{6}""")
        private val WORD = Regex("""[A-Za-z]+""")
        private val SAFE_LINK = Regex("""^(https?|mailto):""", RegexOption.IGNORE_CASE)
        private val BULLETS = listOf("•", "◦", "▪")

        private val CSS = """
            body{margin:0;background:#e0e0e0;font-family:sans-serif}
            .doc{background:#fff;color:#222;margin:8px;padding:20px 18px;line-height:1.4;overflow-wrap:anywhere}
            p{margin:0 0 .5em}
            h1,h2,h3,h4,h5,h6{margin:.7em 0 .4em;line-height:1.25}
            table{border-collapse:collapse;margin:.5em 0}
            td{border:1px solid #bbb;padding:4px 6px;vertical-align:top}
            td p{margin:0}
            img{max-width:100%;height:auto}
            hr.pb{border:0;border-top:1px dashed #bbb;margin:1.2em 0}
            .m{display:inline-block;min-width:1.5em;text-indent:0}
        """.trimIndent().replace("\n", "")

        fun convert(input: InputStream): String {
            val parts = HashMap<String, ByteArray>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val bytes = zip.readBytes()
                    if (bytes.size <= MAX_PART_BYTES) parts[entry.name.removePrefix("/")] = bytes
                }
            }
            return DocxConverter(parts).convert()
        }

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }

        private fun formatNumber(n: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "decimalZero" -> n.toString().padStart(2, '0')
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            val letter = 'A' + (n - 1) % 26
            return letter.toString().repeat((n - 1) / 26 + 1)
        }

        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var rest = n
            return buildString {
                for (i in values.indices) while (rest >= values[i]) { append(symbols[i]); rest -= values[i] }
            }
        }
    }
}
