package ohd.platform

/**
 * 跨平台数值/字符串格式化：替代 `String.format(...)` 与 `"%.1f".format(x)`。
 *
 * ## 为什么自己写
 *
 * `String.format` 是 JDK 独有的扩展；K/N 上 `Unresolved reference 'format'`。
 * 仓库里有 120+ 处调用，格式符却只有极少数几种（`%d` `%s` `%f` 带精度、`%02x`），
 * 所以这里只实现**实际用到的那些**，不做 printf 全兼容——
 * 不支持的形态**显式抛错**，而不是猜一个可能错的结果出来。
 *
 * ## 支持范围
 *
 * - `%d`（整数）、`%s`（任意值，`null` 输出 `null`）
 * - `%f` / `%.Nf`（浮点，N 位小数，四舍五入）
 * - `%x` / `%02x`（十六进制；宽度 2 时补零）
 * - `%%`（字面百分号）
 * - 数字前的 `0` 与宽度（如 `%02d`）
 *
 * 与 JDK 的差异：`%.1f` 对负数、以及极大/极小值的舍入，采用「乘 10^N 后四舍五入」
 * 而非 `BigDecimal` 语义。对本仓库的输出（MB 数、百分比、秒数）足够；
 * 若将来需要严格一致的 printf 语义，应换 kotlinx 的格式化库而不是继续加分支。
 */
object Fmt {

    /** 单值便捷入口，等价于 `Fmt.of("%.1f", x)`。 */
    fun of(pattern: String, vararg args: Any?): String {
        val sb = StringBuilder(pattern.length + 16)
        var ai = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c != '%') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 < pattern.length && pattern[i + 1] == '%') {
                sb.append('%')
                i += 2
                continue
            }
            // 解析 %[0][width][.precision]spec
            var j = i + 1
            var zeroPad = false
            if (j < pattern.length && pattern[j] == '0') {
                zeroPad = true
                j++
            }
            var width = 0
            while (j < pattern.length && pattern[j].isDigit()) {
                width = width * 10 + (pattern[j] - '0')
                j++
            }
            var precision = -1
            if (j < pattern.length && pattern[j] == '.') {
                j++
                precision = 0
                while (j < pattern.length && pattern[j].isDigit()) {
                    precision = precision * 10 + (pattern[j] - '0')
                    j++
                }
            }
            if (j >= pattern.length) {
                // 悬空的 '%'：按字面保留，不猜
                sb.append('%')
                i++
                continue
            }
            val spec = pattern[j]
            val arg = args.getOrNull(ai)
            ai++
            when (spec) {
                'd' -> {
                    val v = (arg as? Number)?.toLong() ?: arg?.toString()?.toLongOrNull() ?: 0L
                    sb.append(pad(v.toString(), width, zeroPad))
                }
                's' -> sb.append(pad(arg?.toString() ?: "null", width, zeroPad))
                'f' -> sb.append(formatDouble((arg as? Number)?.toDouble() ?: 0.0, precision, width, zeroPad))
                'x' -> {
                    val v = (arg as? Number)?.toLong() ?: 0L
                    sb.append(pad(v.toString(16), width, zeroPad))
                }
                else -> throw IllegalArgumentException("Fmt 不支持格式符 '%$spec'（pattern=$pattern）")
            }
            i = j + 1
        }
        return sb.toString()
    }

    private fun pad(s: String, width: Int, zeroPad: Boolean): String {
        if (width <= 0 || s.length >= width) return s
        val sb = StringBuilder(width)
        val fill = if (zeroPad) '0' else ' '
        // 负数补零要落在一号后面（-007 而非 00-7）
        if (zeroPad && s.startsWith("-")) {
            sb.append('-')
            repeat(width - s.length) { sb.append(fill) }
            sb.append(s, 1, s.length)
        } else {
            repeat(width - s.length) { sb.append(fill) }
            sb.append(s)
        }
        return sb.toString()
    }

    /**
     * 定点小数，**按 JDK `String.format("%.Nf", v)` 的语义**。
     *
     * ## 为什么不能直接「乘 10^N 再四舍五入」
     *
     * `1.005` 的二进制实际是 `1.00499999...`，直接乘加会得到 `1.00`，
     * 而 JDK 输出 `1.01`。JDK 的做法是**先取 double 的最短十进制表示**（`"1.005"`），
     * 再对这个十进制串做半数进位——也就是人眼看到的那个数。
     *
     * 本仓库的 `%.1f MB`、`%.1f%%` 都是给人看的报告数字，必须与这个直觉一致，
     * 否则同一份数据在新旧版本里会显示成不同的值。
     *
     * ## 实现
     *
     * 1. `v.toString()` 得到最短短表示（Kotlin 的 Double.toString 与 Java 同算法）
     * 2. 拆成符号 / 整数位 / 小数位，按第 p+1 位决定进位
     * 3. 全程用十进制串运算，不碰浮点，避免二次误差
     */
    private fun formatDouble(v: Double, precision: Int, width: Int, zeroPad: Boolean): String {
        val p = if (precision < 0) 6 else precision
        if (v.isNaN()) return pad("NaN", width, zeroPad)
        if (v.isInfinite()) return pad(if (v > 0) "Infinity" else "-Infinity", width, zeroPad)

        val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
        val abs = if (v < 0) -v else v

        // 取最短短十进制表示；可能带指数（如 1.0E-4），先展开成普通小数
        val s = abs.toString()
        val plain = expandExponent(s)

        val dot = plain.indexOf('.')
        var intPart = if (dot < 0) plain else plain.substring(0, dot)
        var fracPart = if (dot < 0) "" else plain.substring(dot + 1)
        if (intPart.isEmpty()) intPart = "0"

        // 补足到 p+1 位（多留一位判断进位）
        if (fracPart.length < p + 1) {
            fracPart = fracPart + "0".repeat(p + 1 - fracPart.length)
        }

        var digits = (intPart + fracPart).toCharArray()
        val keep = intPart.length + p
        // 第 p+1 位（索引 keep）>= 5 则进位
        val roundUp = digits[keep] >= '5'
        var end = keep
        if (roundUp) {
            var i = keep - 1
            while (i >= 0) {
                if (digits[i] == '9') {
                    digits[i] = '0'
                    i--
                } else {
                    digits[i] = digits[i] + 1
                    break
                }
            }
            if (i < 0) {
                // 全 9 进位：前面补 1
                digits = ("1" + digits.concatToString()).toCharArray()
                end = keep + 1
            }
        }
        // 按 end 截断（end 已含可能的整体进位）
        val kept = digits.concatToString(0, end)
        // kept 形如 "0001005"：整数位 = 去掉小数位后的部分
        val intLen = kept.length - p
        val finalInt = kept.substring(0, intLen).trimStart('0').ifEmpty { "0" }
        val finalFrac = if (p > 0) kept.substring(intLen) else ""

        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(finalInt)
        if (p > 0) {
            sb.append('.')
            sb.append(finalFrac)
        }
        return pad(sb.toString(), width, zeroPad)
    }

    /** 把 `1.0E-4` 这类指数形式展开成 `0.0001`。 */
    private fun expandExponent(s: String): String {
        val eIdx = s.indexOfFirst { it == 'E' || it == 'e' }
        if (eIdx < 0) return s
        val mantissa = s.substring(0, eIdx)
        val exp = s.substring(eIdx + 1).toIntOrNull() ?: return s
        val dot = mantissa.indexOf('.')
        val intPart = if (dot < 0) mantissa else mantissa.substring(0, dot)
        val fracPart = if (dot < 0) "" else mantissa.substring(dot + 1)
        val digits = intPart + fracPart
        // 当前小数点位置
        val pointPos = intPart.length + exp
        return when {
            pointPos <= 0 -> "0." + "0".repeat(-pointPos) + digits
            pointPos >= digits.length -> digits + "0".repeat(pointPos - digits.length)
            else -> digits.substring(0, pointPos) + "." + digits.substring(pointPos)
        }
    }
}

/** `"%.1f".format(x)` 的跨平台替身：`fmt("%.1f", x)`。 */
fun fmt(pattern: String, vararg args: Any?): String = Fmt.of(pattern, *args)
