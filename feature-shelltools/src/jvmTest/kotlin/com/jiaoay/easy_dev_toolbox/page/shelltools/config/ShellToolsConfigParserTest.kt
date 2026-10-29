package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import easydevtoolbox.feature.shelltools.generated.resources.Res
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 解析器四类型 roundtrip 与内置默认资源验收（tasks 1.3 / 2.1）。
 */
class ShellToolsConfigParserTest {

    // ═══════════ 四类型 parse ↔ encode roundtrip ═══════════

    @Test
    fun `normal body roundtrip keeps custom fields`() {
        val body = ChildBody.Normal(value = "adb devices", desc = "列出已连接设备")
        val decoded = ShellToolsConfigParser.parseChildBody(ShellToolsConfigParser.encodeChildBody(body))
        assertEquals(body, decoded, "Normal roundtrip 后字段应完全一致")
    }

    @Test
    fun `multi select body roundtrip keeps custom fields`() {
        val body = ChildBody.MultiSelect(
            items = listOf(
                ChildBody.MultiSelect.MultiSelectItem(name = "选项A", value = "echo a", desc = "描述A"),
                ChildBody.MultiSelect.MultiSelectItem(name = "选项B", value = "echo b", desc = "描述B"),
            )
        )
        val decoded = ShellToolsConfigParser.parseChildBody(ShellToolsConfigParser.encodeChildBody(body))
        assertEquals(body, decoded, "MultiSelect roundtrip 后选项列表应完全一致")
    }

    @Test
    fun `input body roundtrip keeps custom fields`() {
        val body = ChildBody.Input(
            value = "am start -n {package}/.MainActivity",
            params = listOf(
                ChildBody.Input.InputParam(name = "package", desc = "应用包名"),
            )
        )
        val decoded = ShellToolsConfigParser.parseChildBody(ShellToolsConfigParser.encodeChildBody(body))
        assertEquals(body, decoded, "Input roundtrip 后模板与参数应完全一致")
    }

    @Test
    fun `chain body roundtrip keeps custom fields`() {
        val body = ChildBody.Chain(commands = listOf("adb root", "adb remount", "adb push x /system/"))
        val decoded = ShellToolsConfigParser.parseChildBody(ShellToolsConfigParser.encodeChildBody(body))
        assertEquals(body, decoded, "Chain roundtrip 后命令列表应完全一致")
    }

    @Test
    fun `encode emits integer type discriminator for all four types`() {
        val encodedTypes = listOf(
            ChildBody.Normal("cmd", ""),
            ChildBody.MultiSelect(emptyList()),
            ChildBody.Input("", emptyList()),
            ChildBody.Chain(emptyList()),
        ).map { body ->
            (ShellToolsConfigParser.encodeChildBody(body) as kotlinx.serialization.json.JsonObject)
                .get("type")!!.toString().toInt()
        }
        assertEquals(listOf(0, 1, 2, 3), encodedTypes, "encode 应输出整型 type 鉴别字段 0/1/2/3")
    }

    @Test
    fun `unknown body type degrades to empty normal`() {
        val unknown = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type": 9, "name": "", "value": "should be dropped", "desc": ""}"""
        )
        assertEquals(ChildBody.Normal("", ""), ShellToolsConfigParser.parseChildBody(unknown))
    }

    @Test
    fun `decodeConfigs returns null for malformed json and list for valid`() = runBlocking {
        assertEquals(null, ShellToolsConfigParser.decodeConfigs("not a json ["))
        assertEquals(null, ShellToolsConfigParser.decodeConfigs("""{"a":1}"""), "顶层非数组应返回 null")
        assertEquals(emptyList(), ShellToolsConfigParser.decodeConfigs("[]"), "合法空数组应返回空列表")
    }

    // ═══════════ 内置默认资源验收（task 2.1） ═══════════

    @Test
    fun `bundled default resource parses into 3 groups 12 tools with all four body types`() = runBlocking {
        val text = Res.readBytes("files/shell_tools_default.json").decodeToString()
        assertTrue(text.isNotBlank(), "内置默认配置资源应非空")

        // 防泄露守护：内置默认资源只允许通用公开命令，禁止内部包名/action 与个人路径
        val forbidden = listOf("xvendor", "/home/", "/Users/", "MyLib")
        forbidden.forEach { keyword ->
            assertFalse(text.contains(keyword, ignoreCase = true), "内置默认配置不得包含敏感关键词: $keyword")
        }

        val nodes = ShellToolsConfigParser.parse(text)
        val parents = nodes.filterIsInstance<ToolNode.Parent>()
        assertEquals(3, parents.size, "默认配置应解析出 3 个分组")

        val children = parents.flatMap { it.children }.filterIsInstance<ToolNode.Child>()
        assertEquals(12, children.size, "默认配置应解析出 12 个工具")

        val bodyTypes = children.map { child ->
            when (child.body) {
                is ChildBody.Normal -> 0
                is ChildBody.MultiSelect -> 1
                is ChildBody.Input -> 2
                is ChildBody.Chain -> 3
            }
        }.toSet()
        assertEquals(setOf(0, 1, 2, 3), bodyTypes, "默认配置 body 类型分布应覆盖 0/1/2/3")

        // 🔴 2026-09-17 审计修复配套守护：预设命令禁用 cmd.exe 不展开的 shell 专属语法。
        //    修复前两条预设必败：`adb shell kill -9 $(adb shell pidof x)`（cmd 不展开 $()，且命令
        //    替换应放设备端）、`adb pull ... ~/Downloads/...`（cmd 不展开 ~）。
        //    ⚠️ 设备端展开（`adb shell "kill -9 $(pidof x)"`）的 $() 在**引号内**、宿主 shell 不参与
        //    展开——不算违规；只拦宿主侧裸 `$(` 与 `~/`。
        val commands = children.flatMap { child ->
            when (val b = child.body) {
                is ChildBody.Normal -> listOf(b.value)
                is ChildBody.MultiSelect -> b.items.map { it.value }
                is ChildBody.Input -> listOf(b.value)
                is ChildBody.Chain -> b.commands
            }
        }
        commands.forEach { cmd ->
            // 裸 `$(...)` = 宿主侧命令替换（cmd.exe 不展开 ⇒ Windows 必败）。
            // 判定「是否在双引号内」：数 `$(` 之前的双引号个数——奇数 = 引号内（`adb shell "kill -9 $(pidof x)"`
            // 整参数被引号包住、交**设备端** sh 展开，宿主 shell 不参与）= 合法；偶数 = 宿主侧 = 违规。
            val hostSideSubstitution = cmd.indexOf("$(")
                .let { idx -> idx >= 0 && cmd.substring(0, idx).count { it == '"' } % 2 == 0 }
            assertFalse(
                hostSideSubstitution,
                "预设命令不得用宿主侧命令替换 `$(`（cmd.exe 不展开；设备端展开须整体加引号）：$cmd",
            )
            assertFalse(cmd.contains("~/"), "预设命令不得用 `~/` 路径（cmd.exe 不展开 ~）：$cmd")
        }
    }

    @Test
    fun `parse is stable across repeated loads for id determinism`() = runBlocking {
        val text = Res.readBytes("files/shell_tools_default.json").decodeToString()
        val first = ShellToolsConfigParser.parse(text)
        val second = ShellToolsConfigParser.parse(text)
        assertEquals(first, second, "同文本重复解析结果应完全一致（路径式 id 确定性）")

        val firstChild = (first.first() as ToolNode.Parent).children.first()
        assertIs<ToolNode.Child>(firstChild)
        assertNotEquals("", firstChild.id, "缺 id 节点应按路径生成非空 id")
    }
}
