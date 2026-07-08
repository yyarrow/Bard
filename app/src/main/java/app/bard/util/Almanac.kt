package app.bard.util

import android.icu.util.ChineseCalendar
import java.util.Calendar
import kotlin.math.floor

/**
 * 农历与节气工具对象。
 *
 * 实现原理：
 * - 农历：使用 android.icu.util.ChineseCalendar (API 24+)
 * - 节气：寿星通用公式计算 1900-2100 年节气交节日期
 *   公式：日期 = floor(Y × 0.2422 + C) − floor((Y−1)/4)
 *   Y 为年份后两位，C 为节气世纪常数（21世纪适用）
 *
 * 精度说明：
 * - 寿星公式在 21 世纪 (2001-2099) 计算精度约为 ±1 天
 * - 已知特例：个别年份可能出现 ±1 天偏差（可在实际应用中忽略）
 * - 本实现假定年份在 2001-2099 范围内，20 世纪 C 值未实现
 */
object Almanac {
    // 农历月份名（MONTH 从 0 开始）
    private val lunarMonthNames = arrayOf(
        "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"
    )

    // 24 节气名（按公历月份顺序，每月两个）
    private val solarTermNames = arrayOf(
        "小寒", "大寒",           // 1 月
        "立春", "雨水",           // 2 月
        "惊蛰", "春分",           // 3 月
        "清明", "谷雨",           // 4 月
        "立夏", "小满",           // 5 月
        "芒种", "夏至",           // 6 月
        "小暑", "大暑",           // 7 月
        "立秋", "处暑",           // 8 月
        "白露", "秋分",           // 9 月
        "寒露", "霜降",           // 10 月
        "立冬", "小雪",           // 11 月
        "大雪", "冬至"            // 12 月
    )

    // 21 世纪节气世纪常数（按上面的顺序）
    private val solarTermCoefficients = arrayOf(
        5.4055f, 20.12f,          // 小寒、大寒（1 月）
        3.87f, 18.73f,            // 立春、雨水（2 月）
        5.63f, 20.646f,           // 惊蛰、春分（3 月）
        4.81f, 20.1f,             // 清明、谷雨（4 月）
        5.52f, 21.04f,            // 立夏、小满（5 月）
        5.678f, 21.37f,           // 芒种、夏至（6 月）
        7.108f, 22.83f,           // 小暑、大暑（7 月）
        7.5f, 23.13f,             // 立秋、处暑（8 月）
        7.646f, 23.042f,          // 白露、秋分（9 月）
        8.318f, 23.438f,          // 寒露、霜降（10 月）
        7.438f, 22.36f,           // 立冬、小雪（11 月）
        7.18f, 21.94f             // 大雪、冬至（12 月）
    )

    /** 全部 24 节气名，按年内顺序（图鉴用）。 */
    val termNames: List<String> get() = solarTermNames.toList()

    /**
     * 返回农历日期字符串。
     * 例：
     * - "六月十九"
     * - "闰六月十九"
     * - "二月初三"
     */
    fun lunarDate(millis: Long): String {
        val cal = ChineseCalendar().apply { timeInMillis = millis }
        val month = cal.get(ChineseCalendar.MONTH)
        val day = cal.get(ChineseCalendar.DAY_OF_MONTH)
        val isLeapMonth = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1

        val monthStr = if (isLeapMonth) "闰${lunarMonthNames[month]}" else lunarMonthNames[month]
        val dayStr = formatLunarDay(day)

        return "${monthStr}月$dayStr"
    }

    /**
     * 返回当前处于的节气时段名。
     * 逻辑：计算当年 24 个节气的交节日期，找到最近已过或当天的节气
     * 如果在 1 月 1 日前小寒交节，则返回上一年的"冬至"
     */
    fun solarTerm(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1  // Calendar.MONTH 从 0 开始
        val day = cal.get(Calendar.DAY_OF_MONTH)

        // 计算当年 24 个节气的交节日期
        val terms = calculateSolarTerms(year)

        // 找当前日期所在的节气
        for (i in terms.indices) {
            val (termMonth, termDay) = terms[i]
            if (month < termMonth || (month == termMonth && day < termDay)) {
                // 当前日期在这个节气之前，返回前一个节气
                return if (i > 0) {
                    solarTermNames[i - 1]
                } else {
                    // 在第一个节气（小寒）之前，返回上一年的冬至
                    solarTermNames[23]  // 冬至是最后一个（索引 23）
                }
            }
        }

        // 在最后一个节气（冬至）之后，返回冬至
        return solarTermNames[23]
    }

    /**
     * 当天恰逢交节日返回节气名，否则 null。
     */
    fun solarTermToday(millis: Long): String? {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val year = cal.get(Calendar.YEAR)

        val terms = calculateSolarTerms(year)
        for (i in terms.indices) {
            val (termMonth, termDay) = terms[i]
            if (month == termMonth && day == termDay) {
                return solarTermNames[i]
            }
        }
        return null
    }

    /**
     * 返回汉字纪年日期。
     * 例："二〇二六年七月八日"
     * 年份逐位转换；月日用普通中文数字（一~十二月、一~三十一日）
     */
    fun formalDate(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val yearStr = year.toString().map { it.digitToChineseChar() }.joinToString("")
        val monthStr = formatChineseNumber(month)
        val dayStr = formatChineseNumber(day)

        return "${yearStr}年${monthStr}月${dayStr}日"
    }

    // ==================== 私有工具方法 ====================

    /**
     * 格式化农历日期为中文。
     * 初一~初十、十一~十九、二十、廿一~廿九、三十
     */
    private fun formatLunarDay(day: Int): String {
        return when {
            day <= 10 -> {
                val names = arrayOf("", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十")
                names[day]
            }
            day <= 19 -> {
                val names = arrayOf("", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九")
                names[day - 10]
            }
            day == 20 -> "二十"
            day <= 29 -> {
                val names = arrayOf("", "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九")
                names[day - 20]
            }
            day == 30 -> "三十"
            else -> ""
        }
    }

    /**
     * 将 1-31 的数字格式化为中文（用于月和日）。
     * 1~9 → "一"~"九"、10 → "十"、11~19 → "十一"~"十九"、
     * 20 → "二十"、21~29 → "二十一"~"二十九"、30 → "三十"、31 → "三十一"
     */
    private fun formatChineseNumber(num: Int): String {
        val digits = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        return when {
            num < 10 -> digits[num]
            num == 10 -> "十"
            num < 20 -> "十" + digits[num - 10]
            num == 20 -> "二十"
            num < 30 -> "二十" + digits[num - 20]
            num == 30 -> "三十"
            num == 31 -> "三十一"
            else -> ""
        }
    }

    /**
     * 数字字符转换为中文数字字符。
     */
    private fun Char.digitToChineseChar(): Char {
        val chineseDigits = "〇一二三四五六七八九"
        return if (this.isDigit()) chineseDigits[this - '0'] else this
    }

    /**
     * 计算给定年份的 24 个节气交节日期。
     * 返回列表：每个元素为 Pair(month, day)
     * 使用寿星公式：日期 = floor(Y × 0.2422 + C) − floor((Y−1)/4)
     * Y 为年份后两位，C 为节气常数（21 世纪）
     */
    private fun calculateSolarTerms(year: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val y = year % 100  // 年份后两位

        for (i in 0..23) {
            val c = solarTermCoefficients[i]
            // 寿星公式
            val day = floor(y * 0.2422f + c - floor((y - 1) / 4.0f)).toInt()

            // 确定月份（每两个节气一个月）
            val month = (i / 2) + 1

            result.add(Pair(month, day))
        }

        return result
    }
}

/*
验证样例（手工推算）：

验证 1: 2026-07-07
- 年份后两位 Y = 26，小暑 C = 7.108
- 日期 = floor(26 × 0.2422 + 7.108 - floor(25/4)) = floor(6.2572 + 7.108 - 6) = floor(7.3652) = 7
- 2026-07-07 为小暑交节日，solarTermToday(2026-07-07) = "小暑"
- 2026-07-08 处于小暑期间，solarTerm(2026-07-08) = "小暑"

验证 2: 2026-02-04
- 年份后两位 Y = 26，立春 C = 3.87
- 日期 = floor(26 × 0.2422 + 3.87 - floor(25/4)) = floor(6.2572 + 3.87 - 6) = floor(4.1272) = 4
- 2026-02-04 为立春交节日，solarTermToday(2026-02-04) = "立春"

验证 3: 2025-12-21
- 年份后两位 Y = 25，冬至 C = 21.94
- 日期 = floor(25 × 0.2422 + 21.94 - floor(24/4)) = floor(6.055 + 21.94 - 6) = floor(21.995) = 21
- 2025-12-21 为冬至交节日，solarTermToday(2025-12-21) = "冬至"

验证 4: 农历日期 2026-07-08（公历）
- ChineseCalendar: 当前应为农历六月初二左右（需实际运行验证）
- 格式化测试：初一、初十、十一、二十、廿一、三十 等

验证 5: 汉字纪年 2026-07-08
- 年份：2026 → "二〇二六"
- 月：07 → "七"
- 日：08 → "八"
- 结果："二〇二六年七月八日"
*/
