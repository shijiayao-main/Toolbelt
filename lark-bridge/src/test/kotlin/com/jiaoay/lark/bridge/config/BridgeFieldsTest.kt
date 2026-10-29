package com.jiaoay.lark.bridge.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CDS §13.2 / §16.8] BridgeFields.ALL 逐字段恒等 + id 唯一 + group/applyMode 非空 + 统计断言。
 * 🔴 统计口径以 §16.8 为规范数据；§16.8 的 Choice 清单误列 `observability.logLevel`（该字段不存在），
 *    实际 6 个 Choice 以 §5.2 逐行表为准（详见完成报告⑧）——本测试只断言**计数**不断言 Choice 具体成员名单，规避该文档笔误。
 */
class BridgeFieldsTest {

    private val all = BridgeFields.ALL
    private val default = DefaultBridgeConfig.default()

    @Test
    fun `逐 field set-get 恒等（读改写往返不丢值）`() {
        all.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            val f = field as BridgeField<Any?>
            val value = f.get(default)
            val rewritten = f.set(default, value)
            assertEquals(value, f.get(rewritten), "字段 ${field.id} 的 set(get(x)) 应恒等")
            // set 后其余不变：整体等价（写回同值应得到等价配置）
            assertEquals(default, rewritten, "字段 ${field.id} 写回同值后配置应等价")
        }
    }

    @Test
    fun `id 全局唯一（47 个无重复）`() {
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "BridgeField.id 必须全局唯一")
    }

    @Test
    fun `group 与 applyMode 每字段非空`() {
        all.forEach {
            assertTrue(ConfigGroup.entries.contains(it.group), "${it.id} group 非法")
            assertTrue(it.applyMode == ApplyMode.HOT || it.applyMode == ApplyMode.RESTART, "${it.id} applyMode 非法")
        }
    }

    @Test
    fun `字段总数 47`() {
        // 69 → v12→v13：− access 三字段 + trustedUsers = 67；→ v13→v14：− 能力域开关 19 项 = 48；→ v14→v15：− xlogDecodeEnabled = 47
        assertEquals(47, all.size)
    }

    @Test
    fun `applyMode 统计 42 HOT 5 RESTART`() {
        assertEquals(42, all.count { it.applyMode == ApplyMode.HOT })
        assertEquals(5, all.count { it.applyMode == ApplyMode.RESTART })
    }

    @Test
    fun `RESTART 5 字段清单逐字（防漂移；startupProbe 开关已随 v13→v14 退役）`() {
        val expected = setOf(
            "connection.appId", "connection.appSecret", "connection.region",
            "workspace.root", "workspace.extraRoots",
        )
        val actual = all.filter { it.applyMode == ApplyMode.RESTART }.map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `分组字段数统计（AGENT 14；其余组不动）`() {
        assertEquals(6, all.count { it.group == ConfigGroup.CONNECTION })
        assertEquals(1, all.count { it.group == ConfigGroup.ACCESS })
        assertEquals(2, all.count { it.group == ConfigGroup.WORKSPACE })
        assertEquals(7, all.count { it.group == ConfigGroup.RUNTIME })
        assertEquals(14, all.count { it.group == ConfigGroup.AGENT })
        assertEquals(9, all.count { it.group == ConfigGroup.FILE_MESSAGE })
        assertEquals(8, all.count { it.group == ConfigGroup.OBSERVABILITY })
    }

    @Test
    fun `editor 类型计数（Number23 Switch10 Text5 Choice2 Tags2 Multiline2 Directory1 Secret2）`() {
        assertEquals(23, all.count { it.editor is Editor.Number })
        assertEquals(10, all.count { it.editor is Editor.Switch })
        assertEquals(5, all.count { it.editor is Editor.Text })
        assertEquals(2, all.count { it.editor is Editor.Choice })
        assertEquals(2, all.count { it.editor is Editor.Tags })
        assertEquals(2, all.count { it.editor is Editor.Multiline })
        assertEquals(1, all.count { it.editor is Editor.Directory })
        assertEquals(2, all.count { it.editor is Editor.Secret })
    }

    @Test
    fun `每个 Choice 字段的 labels 覆盖全部 options（G-E：展示文案属声明式字段表）`() {
        val choices = all.mapNotNull { f -> (f.editor as? Editor.Choice)?.let { f.id to it } }
        assertEquals(2, choices.size, "Choice 字段应为 2 个")
        choices.forEach { (id, choice) ->
            choice.options.forEach { option ->
                assertTrue(
                    choice.labels.containsKey(option) && choice.labels.getValue(option).isNotBlank(),
                    "$id 的 option '$option' 缺展示 label（UI 会 fallback 到 raw 值，须补齐中文标签）",
                )
            }
        }
    }

    @Test
    fun `secret 恰好 2 项：connection-appSecret 与 fileMessage-xlogPrivateKey`() {
        val secrets = all.filter { it.secret }
        assertEquals(2, secrets.size)
        assertEquals(setOf("connection.appSecret", "fileMessage.xlogPrivateKey"), secrets.map { it.id }.toSet())
        secrets.forEach { assertTrue(it.editor is Editor.Secret, "${it.id} 应使用 Secret 编辑器") }
    }

    @Test
    fun `maxFileSizeBytes 启用 displayScale-unitSuffix（§16-2，其余 Number 用默 认）`() {
        val field = all.first { it.id == "fileMessage.maxFileSizeBytes" }
        val number = field.editor as Editor.Number
        assertEquals(1048576L, number.displayScale)
        assertEquals(" MB", number.unitSuffix)
        assertEquals(1048576L, number.min)
        assertEquals(4294967296L, number.max, "分片批（2026-09-13）：Range 突破平台 100MB 限后编辑器上限抬 4GB")
        // 其余 Number 字段 displayScale 默认 1——除 2026-09-11 引入的配额字段（同 MB 显示口径）
        // （P3 批只删 reuseFileKey/memoTtlHours/memoMaxEntries 三件套——maxSizeBytes/maxCount 是
        //  产物回传上限，保留且仍走 MB 显示）
        val scaledIds = setOf(
            "fileMessage.maxFileSizeBytes", "fileMessage.perUserQuotaBytes", "fileMessage.totalQuotaBytes",
            "agent.artifacts.maxSizeBytes",
        )
        all.filter { it.editor is Editor.Number && it.id !in scaledIds }.forEach {
            assertEquals(1L, (it.editor as Editor.Number).displayScale, "${it.id} 应用默认 displayScale=1")
        }
        scaledIds.minus("fileMessage.maxFileSizeBytes").forEach { id ->
            val n = all.first { it.id == id }.editor as Editor.Number
            assertEquals(1048576L, n.displayScale, "$id 配额字段应 MB 显示")
            assertEquals(" MB", n.unitSuffix)
        }
    }

    @Test
    fun `extraRoots 启用 pickDirectory（§16-3，其余 Tags 为 false）`() {
        val extraRoots = all.first { it.id == "workspace.extraRoots" }
        assertTrue((extraRoots.editor as Editor.Tags).pickDirectory)
        all.filter { it.editor is Editor.Tags && it.id != "workspace.extraRoots" }.forEach {
            assertEquals(false, (it.editor as Editor.Tags).pickDirectory, "${it.id} pickDirectory 应为 false")
        }
    }

    @Test
    fun `分组 initialExpanded（§16⑥：RUNTIME 与 FILE_MESSAGE 折叠，其余展开）`() {
        assertEquals(false, ConfigGroup.RUNTIME.initialExpanded)
        assertEquals(false, ConfigGroup.FILE_MESSAGE.initialExpanded)
        assertTrue(ConfigGroup.CONNECTION.initialExpanded)
        assertTrue(ConfigGroup.ACCESS.initialExpanded)
        assertTrue(ConfigGroup.WORKSPACE.initialExpanded)
        assertTrue(ConfigGroup.AGENT.initialExpanded)
        assertTrue(ConfigGroup.OBSERVABILITY.initialExpanded)
        assertEquals(2, ConfigGroup.entries.count { !it.initialExpanded })
    }

    // ── tier 分层（2026-09-12 config-tier-split）：基础区清单逐字钉死，防漂移 ──

    @Test
    fun `tier 统计 - BASIC 恰 5、ADVANCED 恰 42（47 字段两分全覆盖）`() {
        assertEquals(5, all.count { it.tier == FieldTier.BASIC })
        assertEquals(42, all.count { it.tier == FieldTier.ADVANCED })
        assertEquals(all.size, all.count { it.tier == FieldTier.BASIC } + all.count { it.tier == FieldTier.ADVANCED },
            "tier 必须两分全覆盖（新增 tier 值须同步本断言）")
    }

    @Test
    fun `BASIC 清单逐字（从零到收发第一条消息的必看项）`() {
        val expected = setOf(
            "connection.appId", "connection.appSecret", "connection.region",
            "access.trustedUsers", "workspace.root",
        )
        val actual = all.filter { it.tier == FieldTier.BASIC }.map { it.id }.toSet()
        assertEquals(expected, actual, "基础区只增不减，进/出须显式裁定并同步本清单")
    }

    @Test
    fun `FieldTier 文案非空（R-19：UI 标题来自枚举）`() {
        FieldTier.entries.forEach { assertTrue(it.title.isNotBlank(), "${it.name} 的 title 不得为空") }
    }


    @Test
    fun `能力域门控字段已全部退役（v13→v14）`() {
        val domainPrefixes = listOf(
            "agent.sysTools.", "agent.devTools.", "agent.selfStatus.", "agent.startupProbe.",
        )
        val survivors = all.map { it.id }.filter { id -> domainPrefixes.any { id.startsWith(it) } }
        assertTrue(survivors.isEmpty(), "能力域开关不得复辟，实际残留：$survivors")
        // wsTools 只剩数值钳制项
        val wsFields = all.map { it.id }.filter { it.startsWith("agent.wsTools.") }
        assertEquals(listOf("agent.wsTools.runTaskTimeoutMinutes"), wsFields)
    }

    @Test
    fun `bespoke 卡字段不出现在 BridgeFields（§6-3 routes 等列表型不进字段表）`() {
        assertTrue(all.none { it.id == "routes" || it.id.startsWith("routes.") })
        assertTrue(all.none { it.id.contains("scanQr") || it.id.contains("logConsole.lines") })
    }
}
