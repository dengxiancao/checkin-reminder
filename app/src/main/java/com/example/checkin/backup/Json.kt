// 职责：极简 JSON 编解码器（零依赖，可被 JVM 单测覆盖）
package com.example.checkin.backup

/**
 * 极简 JSON 编解码器。
 *
 * ## 为什么不直接用 `org.json`
 * 1. `org.json` 属于 Android 框架，在 JVM 单元测试里只能拿到「返回默认值」的空壳
 *    （工程启用了 `unitTests.isReturnDefaultValues`），解析结果不可断言，
 *    等于备份恢复这条最需要测试的链路**完全无法被单测保护**；
 * 2. 引入 Gson / kotlinx-serialization 会新增运行时依赖 ——
 *    对一个把「数据只存本机」当作隐私卖点的应用，供应链面越小越好，
 *    而本工程的备份结构由自己生成、格式完全可控，不值得为此承担依赖成本。
 *
 * ## 覆盖范围
 * 写出端：本工程自己调用，只产出保守 JSON（所有非 ASCII 字符转义为 `\uXXXX`）。
 * 读取端：**按 JSON 标准实现**，支持对象 / 数组 / 字符串（含全部转义与 Unicode 代理对）/
 * 数字 / `true` / `false` / `null`，因此对格式偏差与非法输入都能给出可读错误而不是崩溃。
 */
object Json {

    /** JSON 语法错误。携带位置信息，便于定位损坏的备份文件 */
    class JsonException(message: String) : Exception(message)

    /**
     * 解析 JSON 文本。
     *
     * @return 对象 -> `Map<String, Any?>`；数组 -> `List<Any?>`；
     *         字符串 -> [String]；数字 -> [Double]；布尔 -> [Boolean]；`null` -> `null`
     * @throws JsonException 语法错误或存在多余内容
     */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw JsonException("JSON 解析结束后仍有多余内容（位置 ${parser.position}）")
        }
        return value
    }

    /** 转义为 JSON 字符串字面量内容（**不含**两侧引号） */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        text.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /** 生成带引号的 JSON 字符串字面量 */
    fun quote(text: String): String = "\"" + escape(text) + "\""

    /** 把 Double 写成 JSON 数字；NaN/Infinity 无法表达，降级为 null（由调用方保证不出现） */
    fun number(value: Double): String =
        if (value.isFinite()) value.toString() else "null"

    // ---------------------------------------------------------------- 解析实现

    private class Parser(private val text: String) {
        var position: Int = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isJsonWhitespace()) position++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("JSON 内容为空或意外结束")
            return when (val ch = text[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                else -> {
                    if (ch == '-' || ch.isDigit()) parseNumber()
                    else throw JsonException("位置 $position 出现非法字符 '$ch'")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { position++; return map }

            while (true) {
                skipWhitespace()
                if (peek() != '"') {
                    throw JsonException("位置 $position 期望对象键（字符串）")
                }
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> { position++; return map }
                    else -> throw JsonException("位置 $position 期望 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { position++; return list }

            while (true) {
                list += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> { position++; return list }
                    else -> throw JsonException("位置 $position 期望 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("字符串未闭合")
                val ch = text[position++]
                when {
                    ch == '"' -> return sb.toString()

                    ch == '\\' -> {
                        if (atEnd()) throw JsonException("字符串转义未完成")
                        when (val esc = text[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val code = readHex4()
                                sb.append(code.toChar())
                            }

                            else -> throw JsonException("未知转义 '\\$esc'（位置 $position）")
                        }
                    }

                    ch < ' ' -> throw JsonException("字符串中出现未转义控制字符（位置 ${position - 1}）")

                    else -> sb.append(ch)
                }
            }
        }

        /**
         * 读取 `\uXXXX`。
         *
         * 需要处理 UTF-16 代理对（如 emoji）：高位代理 `D800-DBFF` 后必须紧跟
         * 一个 `\uDC00-\uDFFF` 低位代理，两者合起来才是一个完整字符。
         * 直接把两个 char 依次 append 到 StringBuilder 即可由 JVM 自动合成。
         */
        private fun readHex4(): Int {
            if (position + 4 > text.length) throw JsonException("\\u 转义不完整")
            var value = 0
            repeat(4) {
                val c = text[position++]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw JsonException("\\u 转义含非法十六进制字符 '$c'")
                }
                value = value * 16 + digit
            }
            return value
        }

        private fun parseNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && text[position].isDigit()) position++
            if (peek() == '.') {
                position++
                while (!atEnd() && text[position].isDigit()) position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                while (!atEnd() && text[position].isDigit()) position++
            }
            val raw = text.substring(start, position)
            return raw.toDoubleOrNull()
                ?: throw JsonException("位置 $start 的数字无法解析：'$raw'")
        }

        private fun expect(ch: Char) {
            if (peek() != ch) throw JsonException("位置 $position 期望 '$ch'，实际 '${peek()?.toString() ?: "EOF"}'")
            position++
        }

        private fun expectLiteral(literal: String) {
            if (position + literal.length > text.length ||
                text.substring(position, position + literal.length) != literal
            ) {
                throw JsonException("位置 $position 期望字面量 '$literal'")
            }
            position += literal.length
        }

        private fun peek(): Char? = if (position < text.length) text[position] else null
    }

    private fun Char.isJsonWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r'
}
