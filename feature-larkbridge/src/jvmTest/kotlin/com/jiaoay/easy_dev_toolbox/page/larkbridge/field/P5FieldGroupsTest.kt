package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import com.jiaoay.lark.bridge.config.ApplyMode
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [P5FieldGroups] 五分区守护（分区定义即本测试断言面，自足）：
 *
 * 🔴 全字段覆盖 + 互斥 + 逐分区计数逐字断言是五分区的正确性根基——
 * 分区是**显式清单**（不再有派生兜底），新增 BridgeField 不标分区即测试红（兜底分区须显式登记，
 * 比「静默落高级折叠」严）。计数对账以 BridgeFieldsTest 为真源：47 = 主卡 3 + 能力域 0（已随
 * v13→v14 退役）+ 分区 44（连接与准入 9｜任务行为与限额 18｜文件消息 9｜可观测性 8）。
 * DOMAIN_FACE 恒空集，仅留作覆盖对账判据。
 * L2 授权面 2 件（L2_FACE）已随 `agent.nativeTools` 段退役删除。
 */
class P5FieldGroupsTest {

    private val allIds: Set<String> = BridgeFields.ALL.map { it.id }.toSet()

    /** 🔒 主卡与分区并集互斥：任一字段不得同时属于主卡与分区（双渲染入口即缺陷）。 */
    @Test
    fun botIdentityAndZonesAreDisjoint() {
        val overlap = P5FieldGroups.BOT_IDENTITY intersect P5FieldGroups.ZONE_IDS
        assertTrue(overlap.isEmpty(), "主卡与分区并集必须互斥，实际重叠：$overlap")
    }

    /** 🔒 分区两两互斥：任一字段不得同时属于两个分区（重复渲染即缺陷）。 */
    @Test
    fun zonesArePairwiseDisjoint() {
        val duplicated = P5FieldGroups.ZONE_IDS.toList().groupingBy { it }.eachCount()
            .filterValues { it > 1 }
        // ZONE_IDS 已是 Set（去重）——互斥必须用**列举序**复核（flatMap 保序，重复即真重复）
        val listed = P5FieldGroups.ZONES.flatMap { it.fieldIds }
        assertEquals(listed.size, listed.distinct().size, "分区清单列举序不得有重复 id：${listed.groupingBy { it }.eachCount().filterValues { it > 1 }}")
        assertTrue(duplicated.isEmpty(), "ZONE_IDS 不应有去重痕迹")
    }

    /** 🔒 主卡 + 六分区并集 == 全部字段 id（无孤儿、无越界——新增字段不标分区即红）。 */
    @Test
    fun zonesPlusMainCardCoverAllFieldIds() {
        // 能力域门控恒空——并集判据「主卡 3 + 分区 44 = 47」
        val covered = P5FieldGroups.BOT_IDENTITY + P5FieldGroups.DOMAIN_FACE + P5FieldGroups.ZONE_IDS
        assertEquals(
            allIds, covered,
            "主卡 + 能力域门控 + 六分区并集必须恰等于 BridgeFields.ALL 的 id 全集（无孤儿无越界）",
        )
    }

    /** 🔒 DOMAIN_FACE 与主卡/分区两两互斥：DOMAIN_FACE 恒空集（原 18 件能力域开关已退役），保留仅作互斥对账。 */
    @Test
    fun domainFaceIsDisjointFromOtherFaces() {
        val overlapMain = P5FieldGroups.DOMAIN_FACE intersect P5FieldGroups.BOT_IDENTITY
        assertTrue(overlapMain.isEmpty(), "能力域面与主卡必须互斥，实际重叠：$overlapMain")
        val overlapZones = P5FieldGroups.DOMAIN_FACE intersect P5FieldGroups.ZONE_IDS
        assertTrue(overlapZones.isEmpty(), "能力域面与分区并集必须互斥，实际重叠：$overlapZones")
    }

    /** 🔒 四分区计数逐字（9/18/9/8 + 主卡 3 + 能力域 0 = 47；真源差异见 P5FieldGroups 类头）。 */
    @Test
    fun zoneCountsMatchDesignSource() {
        val expected = listOf(
            "连接与准入" to 9,
            "任务行为与限额" to 18,
            "文件消息" to 9,
            "可观测性" to 8,
        )
        assertEquals(
            expected,
            P5FieldGroups.ZONES.map { it.title to it.fieldIds.size },
            "四分区计数逐字钉死（与 BridgeFieldsTest 47 字段对账）",
        )
        assertEquals(
            47,
            P5FieldGroups.BOT_IDENTITY.size +
                P5FieldGroups.DOMAIN_FACE.size + P5FieldGroups.ZONE_IDS.size,
            "主卡 3 + 能力域门控 0 + 分区并集 44 == 47（并集判据）",
        )
        assertEquals(
            0, P5FieldGroups.DOMAIN_FACE.size,
            "能力域门控已整段退役（注册面不再有配置闸）",
        )
    }

    /** 🔒 主卡恰 3 字段，清单逐字（agent-first 身份面）。 */
    @Test
    fun botIdentityHoldsExactlyThreeFields() {
        assertEquals(
            setOf("agent.persona", "agent.systemPrompt", "agent.groupMode"),
            P5FieldGroups.BOT_IDENTITY,
            "主卡字段清单逐字钉死（预览稿「字段迁移对照表」身份面行）",
        )
        assertEquals(3, P5FieldGroups.BOT_IDENTITY_FIELDS.size, "FIELDS 派生面与 id 清单同尺寸")
    }

    /** 🔒 分区内 id 全部存在于 BridgeFields.ALL（清单不得越界声明）。 */
    @Test
    fun zoneFieldIdsAllExistInBridgeFields() {
        P5FieldGroups.ZONES.forEach { zone ->
            val unknown = zone.fieldIds.filter { it !in allIds }
            assertTrue(unknown.isEmpty(), "分区「${zone.title}」声明了不存在的字段 id：$unknown")
        }
    }

    /** 🔒 FIELDS 派生面只含分区清单内的字段（filter 语义反证：声明序 + 归属一致）。 */
    @Test
    fun derivedFieldListsMatchTheirIdSets() {
        assertTrue(
            P5FieldGroups.BOT_IDENTITY_FIELDS.all { it.id in P5FieldGroups.BOT_IDENTITY },
            "BOT_IDENTITY_FIELDS 不得混入清单外字段",
        )
        assertTrue(
            P5FieldGroups.DOMAIN_FACE_FIELDS.all { it.id in P5FieldGroups.DOMAIN_FACE },
            "DOMAIN_FACE_FIELDS 不得混入清单外字段",
        )
        assertTrue(
            P5FieldGroups.ZONE_FIELDS.all { it.id in P5FieldGroups.ZONE_IDS },
            "ZONE_FIELDS 不得混入清单外字段",
        )
        assertTrue(
            P5FieldGroups.ZONE_FIELDS.none { it.id in P5FieldGroups.BOT_IDENTITY },
            "ZONE_FIELDS 不得残留主卡字段（互斥传递）",
        )
    }

    // ───────────────────────── 行级派生（isModified / RESTART_IDS） ─────────────────────────

    /** 🔒 isModified：默认配置零修改、改一个字段只标记该字段（值 ≠ DefaultBridgeConfig.default()）。 */
    @Test
    fun isModifiedDetectsChangedFieldOnly() {
        val default = DefaultBridgeConfig.default()
        // 默认配置：全部字段零修改
        assertTrue(
            BridgeFields.ALL.none { P5FieldGroups.isModified(it, default) },
            "默认配置下不应有任何字段被标记已修改",
        )
        // 改 runtime.taskTimeoutSeconds 60→90：只此一件 modified
        val field = BridgeFields.ALL.first { it.id == "runtime.taskTimeoutSeconds" }
        @Suppress("UNCHECKED_CAST")
        val typed = field as BridgeField<Any?>
        val changed = typed.set(default, 90L)
        assertTrue(P5FieldGroups.isModified(field, changed), "改动后该字段应标记已修改")
        val modified = P5FieldGroups.modifiedIds(changed)
        assertEquals(setOf("runtime.taskTimeoutSeconds"), modified, "只应标记被改的字段")
    }

    /** 🔒 RESTART_IDS 恰 10 件，与 BridgeFieldsTest 的 RESTART 清单逐字一致（@restart 过滤的清单源）。 */
    @Test
    fun restartIdsMatchApplyModeMetadata() {
        assertEquals(
            BridgeFields.ALL.filter { it.applyMode == ApplyMode.RESTART }.map { it.id }.toSet(),
            P5FieldGroups.RESTART_IDS,
            "RESTART_IDS 必须逐字等于 applyMode 元数据（现成元数据，不二次维护清单）",
        )
        assertEquals(5, P5FieldGroups.RESTART_IDS.size, "RESTART 恰 5 件（BridgeFieldsTest 同口径）")
    }

    /** 🔒 RESTART 字段必须落在某个渲染面（分区或能力卡）——@restart 过滤结果与可编辑目录不脱节。 */
    @Test
    fun restartIdsAllBelongToRenderedFaces() {
        val outside = P5FieldGroups.RESTART_IDS - P5FieldGroups.ZONE_IDS -
            P5FieldGroups.DOMAIN_FACE
        assertTrue(outside.isEmpty(), "RESTART 字段不得落在全部渲染面之外：$outside")
    }

    /** 🔒 ConfigFilter.parse 三态（空 = null / @modified / @restart / 关键词全文匹配）。 */
    @Test
    fun configFilterParsesThreeStates() {
        val default: BridgeConfig = DefaultBridgeConfig.default()
        // 空 = 无过滤
        assertEquals(null, ConfigFilter.parse("", default))
        assertEquals(null, ConfigFilter.parse("   ", default))
        // @modified（大小写不敏感）：默认配置下空集
        val modified = ConfigFilter.parse("@MODIFIED", default)
        assertTrue(modified is ConfigFilter.FilterState.Modified, "@modified 应解析为 Modified 态")
        assertTrue(modified.fields.isEmpty(), "默认配置下 @modified 应为空集")
        // @restart：恰 6 件
        val restart = ConfigFilter.parse("@restart", default)
        assertTrue(restart is ConfigFilter.FilterState.Restart, "@restart 应解析为 Restart 态")
        assertEquals(5, restart.fields.size)
        // 关键词：label（「任务超时」）/ id（taskTimeoutSeconds）/ hint 全文匹配，大小写不敏感
        val byLabel = ConfigFilter.parse("任务超时", default)
        assertTrue(byLabel is ConfigFilter.FilterState.Keyword)
        assertTrue(byLabel.matches.any { it.field.id == "runtime.taskTimeoutSeconds" }, "label 匹配命中")
        val byId = ConfigFilter.parse("TASKTIMEOUTSECONDS", default)
        assertTrue(byId is ConfigFilter.FilterState.Keyword)
        assertTrue(byId.matches.any { it.field.id == "runtime.taskTimeoutSeconds" }, "id 大小写不敏感匹配命中")
        val byHint = ConfigFilter.parse("沙箱拒绝", default)
        assertTrue(byHint is ConfigFilter.FilterState.Keyword)
        assertTrue(byHint.matches.any { it.field.id == "workspace.root" }, "hint 匹配命中")
        // 关键词命中行带分区名前缀
        val zoneTitle = byLabel.matches.first { it.field.id == "runtime.taskTimeoutSeconds" }.zoneTitle
        assertEquals("任务行为与限额", zoneTitle)
        // 无匹配：空结果不炸
        val none = ConfigFilter.parse("不存在的字段xyz", default)
        assertTrue(none is ConfigFilter.FilterState.Keyword && none.matches.isEmpty())
    }
}
