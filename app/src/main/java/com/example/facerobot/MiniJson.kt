package com.example.facerobot

/** Maliit na JSON parser (pure Kotlin) para sa mga sagot ng Tuya cloud. Map / List / String / Double / Boolean / null. */
object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw IllegalArgumentException("sobrang laman pagkatapos ng JSON")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(x: Any?): Map<String, Any?>? = x as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(x: Any?): List<Any?>? = x as? List<Any?>

    fun str(x: Any?): String = when (x) {
        null -> ""
        is Double -> if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else x.toString()
        else -> x.toString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("biglang natapos ang JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("hindi inaasahang '$c' sa $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("maling literal sa $i")
            i += word.length
            return v
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("sirang escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("sirang \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("hindi naisara ang string")
        }

        private fun list(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("hindi naisara ang array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw IllegalArgumentException("inaasahang , o ] sa $i")
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("inaasahang key sa $i")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("inaasahang : sa $i")
                i++
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("hindi naisara ang object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw IllegalArgumentException("inaasahang , o } sa $i")
            }
        }
    }
}
