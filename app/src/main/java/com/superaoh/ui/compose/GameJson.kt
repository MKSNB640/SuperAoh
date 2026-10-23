package com.superaoh.ui.compose

import com.superaoh.core.Json

/**
 * 文明时代2 数据文件的读写。
 *
 * 游戏里的 `assets/game/Governments.json` 不是标准 JSON，而是 libGDX 的宽松格式：
 *
 * ```
 * {
 *     Government: [
 *         {
 *             Name: "Monarchy",      // 键名不加引号
 *             ACCEPTABLE_TAXATION: 0.40,
 *             CIVILIZE_TECH_LEVEL: 2.0f,   // 数值带 f 后缀
 *         },
 *     ],
 * }
 * ```
 *
 * 严格 RFC 8259 解析器（[Json.parse]）遇到这种输入会直接抛异常，
 * 所以读取端单独实现一个宽松解析器。
 *
 * 写出端仍然用标准 JSON —— 这是刻意的：
 *   · libGDX JsonReader 接受标准 JSON（标准格式是宽松格式的子集）
 *   · 若该版本改用 GovernmentsV2.json + kotlinx.serialization，则只认标准 JSON
 * 两边都安全，不需要根据文件版本切换写出策略。
 */
internal object GameJson {

    /** 解析宽松或标准 JSON。对象 → LinkedHashMap，数组 → ArrayList，数字 → Double。 */
    fun parse(text: String): Any? = Parser(text).readValue()

    /** 以标准 JSON 写出（游戏两种读取方式都能接受）。 */
    fun write(value: Any?): String = Json.write(value)

    private class Parser(private val s: String) {
        private var i = 0

        fun readValue(): Any? {
            skip()
            if (i >= s.length) throw IllegalArgumentException("数据文件意外结束")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"', '\'' -> readQuoted(s[i])
                else -> readBare()
            }
        }

        // 跳过空白与注释（游戏文件里偶尔会带行注释）。
        private fun skip() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                        while (i < s.length && s[i] != '\n') i++
                    }
                    c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(s.length)
                    }
                    else -> return
                }
            }
        }

        private fun readObject(): LinkedHashMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skip()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skip()
                if (i >= s.length) throw IllegalArgumentException("对象未闭合")
                val key = when (s[i]) {
                    '"', '\'' -> readQuoted(s[i])
                    else -> readBareString()
                }
                skip()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("键 $key 后缺少冒号")
                i++
                map[key] = readValue()
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    skip()
                    if (i < s.length && s[i] == '}') { i++; return map }  // 容忍尾逗号
                    continue
                }
                if (i < s.length && s[i] == '}') { i++; return map }
                throw IllegalArgumentException("对象未闭合，位置 $i")
            }
        }

        private fun readArray(): ArrayList<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skip()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    skip()
                    if (i < s.length && s[i] == ']') { i++; return list }  // 容忍尾逗号
                    continue
                }
                if (i < s.length && s[i] == ']') { i++; return list }
                throw IllegalArgumentException("数组未闭合，位置 $i")
            }
        }

        private fun readQuoted(quote: Char): String {
            i++ // 开引号
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i + 1, (i + 5).coerceAtMost(s.length))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i++
                } else if (c == quote) {
                    i++
                    return sb.toString()
                } else {
                    sb.append(c)
                    i++
                }
            }
            throw IllegalArgumentException("字符串未闭合")
        }

        /** 读到分隔符为止：空白、`,`、`:`、`}`、`]`。 */
        private fun readBareString(): String {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
                    c == ',' || c == ':' || c == '}' || c == ']'
                ) break
                i++
            }
            if (i == start) throw IllegalArgumentException("位置 $i 处缺少内容")
            return s.substring(start, i)
        }

        /** 裸值：true/false/null、带 f/d/L 后缀的数字，或未加引号的字符串。 */
        private fun readBare(): Any? {
            val token = readBareString()
            when (token) {
                "true" -> return true
                "false" -> return false
                "null" -> return null
            }
            // 去掉 libGDX 的类型后缀：2.0f / 3d / 10L
            val body = token.trimEnd('f', 'F', 'd', 'D', 'l', 'L')
            if (body.isNotEmpty()) {
                body.toLongOrNull()?.let { return it.toDouble() }
                body.toDoubleOrNull()?.let { return it }
            }
            return token
        }
    }
}
