package com.sitecam

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

data class Site(val id: String, val lat: Double, val lon: Double)

object SiteDb {

    /** Accepts the bytes of an .xlsx or .csv file. */
    fun parse(bytes: ByteArray): List<Site> {
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val rows = if (isZip) readXlsx(bytes) else readCsv(bytes)
        return rowsToSites(rows)
    }

    fun save(f: File, sites: List<Site>) {
        f.writeText(sites.joinToString("\n") {
            it.id.replace(',', ' ').replace('\n', ' ') + "," + it.lat + "," + it.lon
        })
    }

    fun load(f: File): List<Site> {
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull {
            val p = it.split(",")
            if (p.size < 3) null else {
                val la = p[p.size - 2].toDoubleOrNull()
                val lo = p[p.size - 1].toDoubleOrNull()
                if (la == null || lo == null) null else Site(p.dropLast(2).joinToString(","), la, lo)
            }
        }
    }

    private fun rowsToSites(rows: List<List<String>>): List<Site> {
        if (rows.size < 2) throw Exception("file is empty")
        val header = rows[0].map { it.trim().lowercase() }
        val latCol = header.indexOfFirst { it.contains("lat") }
        val lonCol = header.indexOfFirst { it.contains("lon") || it.contains("lng") }
        if (latCol < 0 || lonCol < 0) throw Exception("need Latitude and Longitude columns")
        var idCol = header.indexOfFirst {
            !(it.contains("lat") || it.contains("lon") || it.contains("lng")) &&
                (it.contains("site") || it == "id" || it.contains("name") || it.endsWith(" id"))
        }
        if (idCol < 0) idCol = header.indices.firstOrNull { it != latCol && it != lonCol } ?: 0
        val out = ArrayList<Site>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            val id = r.getOrNull(idCol)?.trim().orEmpty()
            val la = r.getOrNull(latCol)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val lo = r.getOrNull(lonCol)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            if (id.isNotEmpty() && la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0)
                out.add(Site(id, la, lo))
        }
        return out
    }

    // ---------- CSV ----------
    private fun readCsv(bytes: ByteArray): List<List<String>> {
        val lines = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val first = lines[0]
        val d = listOf(',', ';', '\t').maxByOrNull { c -> first.count { it == c } } ?: ','
        return lines.map { splitLine(it, d) }
    }

    private fun splitLine(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (ch in line) {
            if (ch == '"') q = !q
            else if (ch == d && !q) { out.add(sb.toString()); sb.setLength(0) }
            else sb.append(ch)
        }
        out.add(sb.toString())
        return out
    }

    // ---------- XLSX (first sheet) ----------
    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        var shared: ByteArray? = null
        var sheet: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                when (e.name) {
                    "xl/sharedStrings.xml" -> shared = z.readBytes()
                    "xl/worksheets/sheet1.xml" -> sheet = z.readBytes()
                }
                e = z.nextEntry
            }
        }
        val s = sheet ?: throw Exception("no sheet found in xlsx")
        val strings = shared?.let { parseShared(it) } ?: emptyList()
        return parseSheet(s, strings)
    }

    private fun parseShared(b: ByteArray): List<String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        val list = ArrayList<String>()
        var sb: StringBuilder? = null
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") sb = StringBuilder() else if (p.name == "t") inT = true
                XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "t") inT = false
                    else if (p.name == "si") { list.add(sb?.toString() ?: ""); sb = null }
            }
            ev = p.next()
        }
        return list
    }

    private fun colIndex(ref: String?): Int {
        var n = 0
        for (ch in ref ?: return 0) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun parseSheet(b: ByteArray, strings: List<String>): List<List<String>> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        val rows = ArrayList<List<String>>()
        var cur: HashMap<Int, String>? = null
        var col = 0
        var type = ""
        var inV = false
        val buf = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = HashMap()
                    "c" -> { col = colIndex(p.getAttributeValue(null, "r")); type = p.getAttributeValue(null, "t") ?: ""; buf.setLength(0) }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) buf.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = buf.toString()
                        val value = if (type == "s") strings.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: "" else raw
                        cur?.put(col, value)
                    }
                    "row" -> {
                        val m = cur
                        if (m != null) {
                            val max = (m.keys.maxOrNull() ?: -1) + 1
                            rows.add(List(max) { m[it] ?: "" })
                        }
                        cur = null
                    }
                }
            }
            ev = p.next()
        }
        return rows
    }
}
