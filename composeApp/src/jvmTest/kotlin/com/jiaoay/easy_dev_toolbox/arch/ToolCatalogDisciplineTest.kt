// P1 · AgentToolCatalog 纪律守护（2026-09-19 平台地基批 A1，扫盘型——防腐化）：
//
// 目标：统一工具目录的注册纪律——
// 1. name 全目录唯一（重名 = 两条链装配出不可预期的工具面）；
// 2. name 匹配 MCP 命名规范 [A-Za-z0-9._-]（P5 外放 MCP 时零改名）；
// 3. 点分命名空间至少两段（log.* / fs.*——防散名）；
// 4. risk 必填且 DESTRUCTIVE 显式（readOnly 不能是「忘了标」的默认——写盘工具漏标 =
//    未来 MCP 外放时被当作只读放行，权限引擎失真）；
// 5. 目录非空（≥12 件存量面——扫描器空转防呆）；
// 6. 撞面互斥三面：别名不得命中其他工具实名/别名、params id 列表唯一、
//    目录外补挂件不得撞目录 name 集（静默错路由/表单字段错位/双注册三类零报错失效）。
//
// 复用既有守护风格（LarkSdkIsolationTest/ToolStructuredDisciplineTest）。
// 🔴 扫盘型守护验收必须 --rerun-tasks --no-build-cache（AGENTS.md 铁律）。
package com.jiaoay.easy_dev_toolbox.arch

import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoBoard
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WriteTodosTool
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ToolCatalogDisciplineTest {

    @Test
    fun `catalog names are unique`() {
        val names = AgentToolCatalog.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "目录内 name 必须唯一：$names")
    }

    @Test
    fun `catalog names match MCP identifier charset`() {
        val regex = Regex("""^[A-Za-z0-9._-]+$""")
        val violations = AgentToolCatalog.all
            .filter { !regex.matches(it.name) }
            .map { it.name }
        if (violations.isNotEmpty()) {
            fail("工具名含 MCP 命名白名单 [A-Za-z0-9._-] 之外字符：$violations")
        }
    }

    @Test
    fun `catalog names are dot-namespaced`() {
        val violations = AgentToolCatalog.all
            .filter { it.name.count { ch -> ch == '.' } < 1 }
            .map { it.name }
        if (violations.isNotEmpty()) {
            fail("工具名必须至少两段点分命名空间（log.*/fs.*——防散名）：$violations")
        }
    }

    @Test
    fun `destructive tools are explicitly flagged`() {
        // 写盘工具清单（按名断言显式 DESTRUCTIVE——漏标 = 外放时权限引擎失真）
        val mustBeDestructive = setOf(
            "log.decrypt", "log.filter_by_tag", "log.filter_by_time", "log.filter_by_range",
            "log.split_by_size",
            "fs.decrypt_file", "fs.extract_archive",
            "sys.repair", "sys.write_config", "sys.service",
        )
        val byName = AgentToolCatalog.all.associateBy { it.name }
        mustBeDestructive.forEach { name ->
            val descriptor = byName[name]
                ?: fail("目录缺少预期工具 [$name]——存量工具被误删？")
            assertEquals(
                AgentToolCatalog.Risk.DESTRUCTIVE,
                descriptor.risk,
                "[$name] 是写盘工具，risk 必须显式 DESTRUCTIVE（外放 MCP 时权限门控依据）",
            )
        }
        // probe/preview/list/read 系必须 READ_ONLY
        val mustBeReadOnly = setOf(
            "log.probe_file", "fs.preview_text", "fs.list_dir",
            "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
            "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
            "fs.extended.convert_timestamp", "web.fetch", "sys.probe", "sys.read_config",
            "sys.verify", "sys.handoff",
        )
        mustBeReadOnly.forEach { name ->
            val descriptor = byName[name]
                ?: fail("目录缺少预期工具 [$name]——存量工具被误删？")
            assertEquals(
                AgentToolCatalog.Risk.READ_ONLY,
                descriptor.risk,
                "[$name] 是只读工具，risk 应为 READ_ONLY",
            )
        }
    }

    @Test
    fun `catalog is non-empty and covers the legacy surface`() {
        assertTrue(
            AgentToolCatalog.all.size >= 12,
            "目录应含存量 12 件（log 五件 + fs 四件 + extended 7 件中的至少部分）——" +
                "实际 ${AgentToolCatalog.all.size}，扫描器空转或存量被误删嫌疑",
        )
        // 桥接旧名经 aliases 可解析（存量 LLM 提示词兼容缝）
        listOf("decrypt_log", "filter_by_tag", "filter_by_time", "filter_by_range", "split_by_size", "probe_file").forEach { alias ->
            assertTrue(
                AgentToolCatalog.resolve(alias) != null,
                "桥接旧名 [$alias] 必须经 aliases 解析到目录实名（存量提示词零迁移）",
            )
        }
    }

    // ── S3 · uiHint 投影纪律（带参命令面板面）──

    /**
     * S3：uiHint ≠ null 的工具必须带 params（palette 参数直填表单的渲染依据——
     * 空表投影会让用户选中命令后无表单直接执行，参数全靠想象）。
     */
    @Test
    fun `uiHint tools must declare params for palette projection`() {
        val withHint = AgentToolCatalog.all.filter { it.uiHint != null }
        // 9 件存量面（LogTaskTools×5 + DecryptTools×1 + KnowledgeTools×1 + AnalyzeLogTool×1 + ask_user×1）
        assertEquals(9, withHint.size, "uiHint 面应为 9 件存量——新增/删减需同步本断言与投影面")
        val missing = withHint.filter { it.params.isEmpty() }.map { it.name }
        if (missing.isNotEmpty()) {
            fail("带 uiHint 的工具必须声明 params（palette 参数直填依据）：$missing")
        }
    }

    /**
     * S3 加固：逐工具硬编码 params id 集（对齐各 Args @Serializable 字段名）——
     * 🔴 params 手工面与 Koog argsType 无编译期绑定，仅断言非空防不住字段错位；
     * 字段名漂移（Args 改名而 params 没跟）= palette 表单填的字段进不了工具。
     */
    @Test
    fun `uiHint tool params ids match the serializable args field names`() {
        val expected: Map<String, Set<String>> = mapOf(
            "log.decrypt" to setOf("file_path"),
            "log.filter_by_tag" to setOf("file_path", "tag_regex", "tag_name"),
            "log.filter_by_time" to setOf("file_path", "target_time", "range_minutes"),
            "log.split_by_size" to setOf("file_path", "size_mb"),
            "log.probe_file" to setOf("file_path"),
            "fs.decrypt_file" to setOf("file_path"),
            "knowledge.search" to setOf("keyword", "repo", "limit"),
            "log.analyze_log" to setOf(
                "log_path", "target_time", "range_minutes",
                "tag_regex", "tag_name", "env_pattern", "stage",
            ),
            // ask_user：fields 嵌套结构不进 ParamSpec（投影裁掉），question 单参满足纪律面
            "user.ask_user" to setOf("question"),
        )
        val byName = AgentToolCatalog.all.associateBy { it.name }
        expected.forEach { (name, ids) ->
            val descriptor = byName[name]
                ?: fail("目录缺少预期工具 [$name]——存量工具被误删？")
            assertEquals(
                ids,
                descriptor.params.map { it.id }.toSet(),
                "[$name] 的 params id 集与 Args @Serializable 字段名不一致（palette 表单字段错位）",
            )
        }
    }

    // ── 撞面互斥守护（resolve 静默取首 ⇒ 撞面零报错；三面共用私有断言函数，
    //    人造撞面样例走同一断言路径自证有牙） ──

    /**
     * 别名撞面不变量：每个 alias 的命中集（其他工具 name 等值或 alias 集包含）必须为空——
     * [AgentToolCatalog.resolve] 按「name 命中或 alias 命中」firstOrNull 静默取首，别名撞
     * 其他工具实名/别名 = 存量提示词经兼容缝被路由到错误工具（零报错错行为）。
     * alias 等于自身 name（自指别名）不构成撞面：resolve 先按 name 命中声明者本人。
     */
    private fun assertAliasesResolveToOwnerOnly(descriptors: List<AgentToolCatalog.AgentToolDescriptor>) {
        val violations = descriptors.flatMap { owner ->
            owner.aliases.mapNotNull { alias ->
                val misroutes = descriptors
                    .filter { it.name != owner.name && (it.name == alias || alias in it.aliases) }
                    .map { it.name }
                if (misroutes.isEmpty()) null else "[$alias]（${owner.name} 声明）撞面：$misroutes"
            }
        }
        if (violations.isNotEmpty()) {
            fail("别名撞面（resolve 静默取首 → 路由到错误工具）：\n${violations.joinToString("\n")}")
        }
    }

    /**
     * params id 列表唯一不变量（非 toSet 集合比对——集合比对会把重复 id 折叠吞掉）：
     * palette 直填表单按 id 绑定取值，同一描述符内重复 id = 表单字段错位。
     */
    private fun assertParamIdsUnique(descriptors: List<AgentToolCatalog.AgentToolDescriptor>) {
        val violations = descriptors.mapNotNull { d ->
            val dups = d.params.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dups.isEmpty()) null else "[${d.name}] params id 重复：$dups"
        }
        if (violations.isNotEmpty()) {
            fail("params id 必须列表唯一（重复 id 在集合比对中被折叠吞掉——表单字段错位）：$violations")
        }
    }

    /**
     * 目录外补挂互斥不变量：补挂件经「catalog 裸装配 → 补挂 → 统一装饰」并入同一
     * ToolRegistry，与目录 name 集撞名 = 同一 registry 双注册。
     */
    private fun assertSideAttachNamesDisjointFromCatalog(
        sideAttachNames: Collection<String>,
        catalogNames: Collection<String>,
    ) {
        val names = catalogNames.toSet()
        val collisions = sideAttachNames.filter { it in names }
        if (collisions.isNotEmpty()) {
            fail("目录外补挂工具撞目录 name 集（同一 ToolRegistry 双注册）：$collisions")
        }
    }

    /** 有牙自证的样例描述符（factory 恒 null——不进入任何 registry，仅走断言路径） */
    private fun descriptor(
        name: String,
        aliases: Set<String> = emptySet(),
        params: List<ParamSpec> = emptyList(),
    ) = AgentToolCatalog.AgentToolDescriptor(
        name = name,
        description = "",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = aliases,
        params = params,
        factory = { _, _, _ -> null },
    )

    @Test
    fun `aliases do not collide with other tool names or aliases`() {
        assertAliasesResolveToOwnerOnly(AgentToolCatalog.all)
    }

    @Test
    fun `aliases resolve to their owning tool name`() {
        // resolve 公开入口交叉核对：每个 alias 必须解析到声明者实名（撞面时静默取首会解析到别家）
        AgentToolCatalog.all.forEach { owner ->
            owner.aliases.forEach { alias ->
                assertEquals(
                    owner.name,
                    AgentToolCatalog.resolve(alias),
                    "alias [$alias] 应解析到声明者 [${owner.name}]——resolve 静默取首会把撞面路由到错误工具",
                )
            }
        }
    }

    @Test
    fun `alias collision assertion has teeth`() {
        val owner = descriptor("log.decrypt", aliases = setOf("decrypt_log"))
        // 别名互撞：两描述符声明同一 alias
        val aliasIntruder = descriptor("log.stats", aliases = setOf("decrypt_log"))
        assertFailsWith<AssertionError>("别名互撞样例必须走红断言路径") {
            assertAliasesResolveToOwnerOnly(listOf(owner, aliasIntruder))
        }
        // 别名撞他工具实名：同罪（resolve 语义下先命中者胜，后者别名失效或错路由）
        val nameIntruder = descriptor("log.stats", aliases = setOf("log.decrypt"))
        assertFailsWith<AssertionError>("别名撞实名样例必须走红断言路径") {
            assertAliasesResolveToOwnerOnly(listOf(owner, nameIntruder))
        }
    }

    @Test
    fun `tool param ids are unique as a list within each descriptor`() {
        assertParamIdsUnique(AgentToolCatalog.all)
    }

    @Test
    fun `param id uniqueness assertion has teeth`() {
        val dupParams = listOf(
            ParamSpec(id = "file_path", label = "a", type = ParamType.FILE),
            ParamSpec(id = "file_path", label = "b", type = ParamType.TEXT),
        )
        assertFailsWith<AssertionError>("params id 重复样例必须走红断言路径（toSet 比对吞不掉本断言）") {
            assertParamIdsUnique(listOf(descriptor("log.decrypt", params = dupParams)))
        }
    }

    @Test
    fun `side-attached write_todos is disjoint from catalog names`() {
        // 补挂形态（亲验）：write_todos 是目录外桌面会话件（TodoBoard 状态投影），三处
        // 装配方均以补挂形式并入 catalog 装配的同一 ToolRegistry；名字取自工具实例（零硬编码）。
        val sideAttachNames = listOf(WriteTodosTool(TodoBoard()).name)
        assertSideAttachNamesDisjointFromCatalog(sideAttachNames, AgentToolCatalog.all.map { it.name })
    }

    @Test
    fun `side-attach disjointness assertion has teeth`() {
        assertFailsWith<AssertionError>("补挂撞名样例必须走红断言路径") {
            assertSideAttachNamesDisjointFromCatalog(
                listOf("log.decrypt"),
                AgentToolCatalog.all.map { it.name },
            )
        }
    }
}
