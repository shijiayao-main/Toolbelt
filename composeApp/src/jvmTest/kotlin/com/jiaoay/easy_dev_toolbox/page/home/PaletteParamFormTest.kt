package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GLB-08 验收网（palette NUMBER 参数放开小数/负数——重设计批 2）：
 *
 * 表单层原 `input.any { !it.isDigit() }` 吞键过滤只放行 0-9，把 schema 层 NUMBER
 * 语义（含小数/负数）提前阉割。本批改「错误时 isError 提示而非吞键」（与 FILE
 * 存在性校验同风格），并给 [paramFormReady] 补 NUMBER 执行门——否则非法串 Enter
 * 直执行会被 argsToJson 的 toDoubleOrNull 静默丢参（mapNotNull 落 null）。
 *
 * 覆盖面：
 * 1. [paramFormReady] NUMBER 门（非法串 false / 合法小数负数 true / optional 空串
 *    保持「未填」语义）；
 * 2. FILE 存在性门回归（paramFormReady 主体重构后的既有行为锚）；
 * 3. 源码断言（吞键过滤清零 + isError supportingText 在位——扫盘型，
 *    🔴 验收须 --rerun-tasks --no-build-cache 防 build cache 假绿）。
 *
 * 执行编码层（argsToJson 先 toLongOrNull 后 toDoubleOrNull）的兼容性锚在
 * [ParamCommandCatalogTest]（本批扩容「小数/负数裸数值」用例）。
 */
class PaletteParamFormTest {

    private fun numberSpec(required: Boolean = true, default: String? = null) =
        ParamSpec(id = "n", label = "n", type = ParamType.NUMBER, default = default, required = required)

    // ── 1 · paramFormReady NUMBER 执行门 ──

    @Test
    fun `paramFormReady rejects invalid NUMBER string for required param`() {
        // 非法串 Enter 不得直执行——argsToJson 对非法 NUMBER 串 mapNotNull 落 null 静默丢参
        assertFalse(
            paramFormReady(listOf(numberSpec()), mapOf("n" to "abc")),
            "字母串非法——执行门拦截（防 argsToJson 静默丢参）",
        )
        assertFalse(paramFormReady(listOf(numberSpec()), mapOf("n" to "1.2.3")), "多小数点非法")
        assertFalse(paramFormReady(listOf(numberSpec()), mapOf("n" to "12-3")), "中置负号非法")
        assertFalse(paramFormReady(listOf(numberSpec()), mapOf("n" to " ")), "纯空格非法（toDouble 不接受）")
    }

    @Test
    fun `paramFormReady accepts decimal and negative NUMBER values`() {
        assertTrue(paramFormReady(listOf(numberSpec()), mapOf("n" to "3.5")), "合法小数放行")
        assertTrue(paramFormReady(listOf(numberSpec()), mapOf("n" to "-30")), "合法负数放行")
        assertTrue(paramFormReady(listOf(numberSpec()), mapOf("n" to "-3.5")), "负小数放行")
        assertTrue(paramFormReady(listOf(numberSpec()), mapOf("n" to "10")), "整型存量路径不回归")
    }

    @Test
    fun `paramFormReady keeps unfilled semantics for optional blank NUMBER`() {
        // 🔴 required=false 空串 = 「未填」（走 default / @Serializable 默认值兜底链），不是非法
        assertTrue(
            paramFormReady(listOf(numberSpec(required = false)), mapOf("n" to "")),
            "optional 空串 = 未填（default 兜底链，勿误判非法）",
        )
        assertTrue(
            paramFormReady(listOf(numberSpec(required = false)), emptyMap()),
            "optional 无值 = 未填",
        )
    }

    @Test
    fun `paramFormReady rejects invalid NUMBER for optional param too`() {
        // optional 非法串同样会被 argsToJson mapNotNull 落 null 静默丢参——门必须同样拦
        assertFalse(
            paramFormReady(listOf(numberSpec(required = false)), mapOf("n" to "abc")),
            "optional 填了非法串同样拦（非空即须合法）",
        )
    }

    @Test
    fun `paramFormReady required blank without default stays not ready`() {
        // 既有语义回归：required 空串 / 无值 = 未就绪；default 合法预填 = 就绪
        assertFalse(paramFormReady(listOf(numberSpec()), mapOf("n" to "")), "required 空串无默认 = 未就绪")
        assertFalse(paramFormReady(listOf(numberSpec()), emptyMap()), "required 无值 = 未就绪")
        assertTrue(paramFormReady(listOf(numberSpec(default = "10")), emptyMap()), "default 预填合法值即就绪")
    }

    // ── 2 · FILE 存在性门回归（paramFormReady 主体重构后的既有行为锚） ──

    @Test
    fun `paramFormReady FILE existence gate unchanged`() {
        val temp = File.createTempFile("palette-form-ready", ".txt").apply { deleteOnExit() }
        assertTrue(
            paramFormReady(
                listOf(ParamSpec(id = "f", label = "f", type = ParamType.FILE)),
                mapOf("f" to temp.absolutePath),
            ),
            "存在文件就绪（既有语义不回归）",
        )
        assertFalse(
            paramFormReady(
                listOf(ParamSpec(id = "f", label = "f", type = ParamType.FILE)),
                mapOf("f" to "/nonexistent/palette-form-ready.bin"),
            ),
            "不存在文件未就绪（既有语义不回归）",
        )
    }

    // ── 3 · 源码断言（扫盘型——🔴 须 --rerun-tasks --no-build-cache） ──

    @Test
    fun `form source no longer swallows non-digit keys and keeps error supporting text`() {
        val source = File(
            locateProjectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteParamForm.kt",
        ).readText()
        assertFalse(
            "isDigit" in source,
            "NUMBER 吞键过滤（isDigit）不得回潮——卡面 acceptance：非法输入 isError 提示不再静默吞键",
        )
        assertTrue(
            "请输入有效数字" in source,
            "NUMBER 非法串的 supportingText（请输入有效数字）在位——isError 反馈语言",
        )
    }
}
