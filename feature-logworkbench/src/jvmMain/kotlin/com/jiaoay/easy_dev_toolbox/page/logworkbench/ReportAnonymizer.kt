package com.jiaoay.easy_dev_toolbox.page.logworkbench

/**
 * 外发内容脱敏器：身份类 PII 值模式识别 + 确定性同值替换（报告导出/送飞书的
 * 内容层前置变换——产物设计用途就是外发）。
 *
 * 核心契约 = **同值恒同替**：同一原值在同一导出实例内恒映射同一替值——首遇分配
 * `类别_N` 编号令牌（[VALUE_PATTERNS] 各判据的 tokenPrefix 提供 `类别`），
 * 后续同值复用。替换只动值不动结构：聚类/diff 计数语义保留，脱敏后产物仍可
 * 送统计链。
 *
 * ## 三族脱敏语义分工（威胁面不同，不冲突不互替）
 * | 语义族 | 判据 | 形态 | 域 |
 * |--------|------|------|----|
 * | [SysOutputSanitizer]（core/system/SysOutputSanitizer.kt） | 凭据键名 | 恒定 MASK 遮蔽 | sys.* 只读诊断输出回灌 LLM |
 * | [SensitiveFilePolicy]（lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/SensitiveFilePolicy.kt） | 文件级敏感路径 | fail-closed 拒发 | send_file 出站 |
 * | 本件 | 身份类 PII 值模式 | 确定性同值替换 | 报告导出/送飞书的外发内容变换 |
 *
 * ## 判据集单源与棘轮
 * [VALUE_PATTERNS] 是类别集 + 正则集 + 施加次序的唯一真源；测试白名单把它
 * 钉死恒等——静默扩判据（新增类别/改正则/调次序）即红：扩判据 = 同时扩遮蔽面
 * 与误伤面，须先改测试白名单再落生产代码。
 *
 * ## 边界契约
 * - 纯函数零 IO；映射表按**导出实例**持有——每次导出新建实例，两次导出同值
 *   可拿不同编号（外发产物之间无编号追溯耦合要求）；
 * - [anonymize] 逐行变换不增删行、不增删换行——产物行号/引用锚不漂移
 *   （报告全文与证据行同走本变换，证据行的行号引用语义保持）；
 * - 非线程安全：单次导出协程独占使用。
 */
class ReportAnonymizer {

    /**
     * 值模式判据单元：[tokenPrefix] = 替值编号前缀（首遇分配 `前缀_N`），
     * [pattern] = 命中判据正则。数据类全等（前缀 + 正则 pattern + options）
     * 是判据集棘轮比对的载体。
     */
    data class SensitiveValuePattern(val tokenPrefix: String, val pattern: Regex)

    /** 原值 → 替值映射（key = tokenPrefix 与原值的复合键，防跨类别撞值） */
    private val aliases = HashMap<String, String>()

    /** 各类别已见不同原值计数（编号令牌的尾号源） */
    private val counters = HashMap<String, Int>()

    /**
     * 全产物脱敏（导出元信息/报告全文/证据行同走本入口）：逐行变换不增删行、
     * 不增删换行；未命中任何判据的行逐字原样。
     */
    fun anonymize(text: String): String =
        text.lineSequence().joinToString(separator = "\n") { line -> anonymizeLine(line) }

    /** 单行变换：按 [VALUE_PATTERNS] 固定次序逐类别替换（次序入棘轮比对面） */
    private fun anonymizeLine(line: String): String {
        var result = line
        for (spec in VALUE_PATTERNS) {
            result = spec.pattern.replace(result) { match -> aliasTokenOf(spec.tokenPrefix, match.value) }
        }
        return result
    }

    /** 首遇分配编号令牌，同值恒复用：`前缀_N`（N = 该类别第 N 个不同原值，按首遇次序） */
    private fun aliasTokenOf(tokenPrefix: String, value: String): String =
        aliases.getOrPut("$tokenPrefix\u0000$value") {
            val ordinal = (counters[tokenPrefix] ?: 0) + 1
            counters[tokenPrefix] = ordinal
            "${tokenPrefix}_$ordinal"
        }

    companion object {

        /**
         * 身份类 PII 值模式判据集（唯一真源，棘轮见类 KDoc）：
         * - VIN：17 位 `[A-HJ-NPR-Z0-9]`，前后非字母数字边界（防吞更长字母数字
         *   串的子串；I/O/Q 不入字符集是 VIN 编码规范）；
         * - IMEI：15 位数字，前后非数字边界（与 13 位毫秒时间戳按长度天然区分）；
         * - MAC：6 组冒号分隔十六进制对（大小写两态同判）；
         * - IPv4：四段点分且每段 ≤255（loopback/内网段同替——同值恒同替保留
         *   结构，段白名单会让同值不同段漂移）；
         * - USER：含用户名绝对路径中的**用户名段**（`/home/<name>/`、
         *   `/Users/<name>/`、`C:\Users\<name>\` 三形态；路径根保留、仅替换段）。
         */
        val VALUE_PATTERNS: List<SensitiveValuePattern> = listOf(
            SensitiveValuePattern("VIN", Regex("""(?<![A-Za-z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])""")),
            SensitiveValuePattern("IMEI", Regex("""(?<![0-9])\d{15}(?![0-9])""")),
            SensitiveValuePattern("MAC", Regex("""(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""")),
            SensitiveValuePattern(
                "IP",
                Regex("""(?<![0-9.])(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)){3}(?![0-9.])"""),
            ),
            SensitiveValuePattern(
                "USER",
                Regex("""(?<=(?:/home/|/Users/))[A-Za-z0-9][A-Za-z0-9._-]*(?=/)|(?<=C:\\Users\\)[A-Za-z0-9][A-Za-z0-9._-]*(?=\\)"""),
            ),
        )
    }
}
