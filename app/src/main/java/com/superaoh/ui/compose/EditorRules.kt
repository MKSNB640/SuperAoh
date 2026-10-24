package com.superaoh.ui.compose

/**
 * 数据编辑器里与平台无关的通用规则。
 *
 * 放在这里而不是各仓库内部，是为了让 JVM 测试（tools 下的脚本）能直接复用：
 * 测试跑的是编辑器真正使用的同一份规则，而不是照着抄一遍。
 */
internal object EditorRules {

    /**
     * 写数值时保留原字段的数值类型。
     *
     * 游戏侧用 getInt / getFloat 之类的强类型读取，把原本的 `7` 写成 `7.0`
     * 通常没事，但把整数写成 `true`、或把布尔键写成数字就会崩。
     * 因此按原值类型回写：整数键保持整数，比例键保持小数。
     */
    fun number(record: MutableMap<String, Any?>, key: String, value: Double) {
        val old = record[key]
        record[key] = when (old) {
            is Int -> value.toInt()
            is Long -> value.toLong()
            is Float -> value.toFloat()
            is Boolean -> value != 0.0
            // 原值不存在或不是数字：按是否为整数自动选 Int / Double
            else -> if (value == Math.floor(value) && !value.isInfinite()) value.toInt() else value
        }
    }

    /*
     * 把滑块输出的连续值对齐到步长网格，并消除浮点噪声。
     *
     * 0.1 累加会得到 0.30000000000000004，直接写回文件会留下丑陋的小数，
     * 某些解析器还可能出现精度分歧。这里统一保留 6 位小数。
     */
    fun snap(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val snapped = Math.round(value / step) * step
        return Math.round(snapped * 1e6) / 1e6
    }

    /** 显示用小数位数：由步长决定，步长越大显示越短。 */
    fun decimals(step: Double): Int = when {
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
}
