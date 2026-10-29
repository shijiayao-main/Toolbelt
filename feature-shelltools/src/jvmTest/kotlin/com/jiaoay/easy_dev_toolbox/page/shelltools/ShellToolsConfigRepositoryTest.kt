package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.DefaultConfigProvider
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolNodeConfig
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolsConfigParser
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ShellToolsConfigRepository 三源管道 / 迁移 / 写路径 / 并发与只读语义验收
 * （tasks 3.1 ~ 3.4、8.1）。
 *
 * 全部经构造注入临时目录文件与默认配置文本，不触碰真实用户目录。
 */
class ShellToolsConfigRepositoryTest {

    private lateinit var tempDir: java.io.File
    private lateinit var mainFile: java.io.File
    private lateinit var customFile: java.io.File

    /** 内置默认配置（注入文本）：1 分组 1 普通工具 */
    private val defaultText = """
        [{"type":0,"name":"默认分组","body":[
            {"type":1,"name":"默认工具","body":{"type":0,"name":"默认工具","value":"echo default","desc":"d"},"showOutput":true}
        ]}]
    """.trimIndent()

    /** 用户主配置：1 分组 1 工具（分组名区别于默认） */
    private val mainText = """
        [{"type":0,"name":"主配置分组","body":[
            {"type":1,"name":"主配置工具","body":{"type":0,"name":"主配置工具","value":"echo main","desc":""},"showOutput":true}
        ]}]
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("edt-repo-test").toFile()
        mainFile = java.io.File(tempDir, "shell_tools.json")
        customFile = java.io.File(tempDir, "shell_tools_custom.json")
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun newRepo(default: String? = defaultText): ShellToolsConfigRepository =
        ShellToolsConfigRepository(
            mainConfigFile = mainFile,
            customConfigFile = customFile,
            defaultConfigProvider = DefaultConfigProvider { default },
        )

    private fun tool(
        name: String,
        id: String? = null,
        body: ChildBody = ChildBody.Normal("echo $name", "desc-$name"),
    ): ShellToolNodeConfig = ShellToolNodeConfig(
        type = 1,
        name = name,
        body = ShellToolsConfigParser.encodeChildBody(body),
        id = id,
    )

    private fun customParentIds(nodes: List<ToolNode>): List<ToolNode.Parent> =
        nodes.filterIsInstance<ToolNode.Parent>().filter { it.isEditable }

    // ═══════════ 3.1 三源合并优先级 ═══════════

    @Test
    fun `fresh user gets bundled defaults silently`() = runBlocking {
        val loaded = newRepo().load()
        assertNull(loaded.mainConfigError, "全新用户（无主配置）应静默使用默认配置")
        val parents = loaded.nodes.filterIsInstance<ToolNode.Parent>()
        assertEquals(listOf("默认分组"), parents.map { it.name })
        assertEquals(1, parents.single().children.size)
    }

    @Test
    fun `valid main config fully replaces defaults`() = runBlocking {
        mainFile.writeText(mainText)
        val loaded = newRepo().load()
        assertNull(loaded.mainConfigError)
        assertEquals(listOf("主配置分组"), loaded.nodes.filterIsInstance<ToolNode.Parent>().map { it.name }, "主配置应完整取代默认配置")
        assertFalse(loaded.nodes.any { (it as? ToolNode.Parent)?.name == "默认分组" })
    }

    @Test
    fun `corrupted main config falls back to defaults with error message`() = runBlocking {
        mainFile.writeText("not a valid json [")
        val loaded = newRepo().load()
        assertNotNull(loaded.mainConfigError, "损坏主配置应回退默认并下发错误提示")
        assertTrue(loaded.mainConfigError.contains("解析失败"), "错误提示应说明解析失败: ${loaded.mainConfigError}")
        assertEquals(listOf("默认分组"), loaded.nodes.filterIsInstance<ToolNode.Parent>().map { it.name }, "应回退默认配置")
    }

    @Test
    fun `blank main config falls back to defaults with error message`() = runBlocking {
        mainFile.writeText("   \n\t  ")
        val loaded = newRepo().load()
        assertNotNull(loaded.mainConfigError)
        assertTrue(loaded.mainConfigError.contains("为空"), "空白文件提示应为空: ${loaded.mainConfigError}")
        assertEquals(listOf("默认分组"), loaded.nodes.filterIsInstance<ToolNode.Parent>().map { it.name })
    }

    @Test
    fun `custom groups are appended after main config`() = runBlocking {
        mainFile.writeText(mainText)
        customFile.writeText(
            """[{"type":0,"name":"我的分组","id":"custom-group:g1","body":[
                {"type":1,"name":"自定义工具","body":{"type":0,"name":"自定义工具","value":"echo custom","desc":""},"showOutput":true,"id":"u1"}
            ]}]"""
        )
        val loaded = newRepo().load()
        val parents = loaded.nodes.filterIsInstance<ToolNode.Parent>()
        assertEquals(listOf("主配置分组", "我的分组"), parents.map { it.name }, "自定义分组应追加在主配置之后")
    }

    @Test
    fun `custom only without main config still shows defaults then custom`() = runBlocking {
        customFile.writeText(
            """[{"type":0,"name":"我的分组","id":"custom-group:g1","body":[
                {"type":1,"name":"自定义工具","body":{"type":0,"name":"自定义工具","value":"echo custom","desc":""},"showOutput":true,"id":"u1"}
            ]}]"""
        )
        val loaded = newRepo().load()
        assertEquals(listOf("默认分组", "我的分组"), loaded.nodes.filterIsInstance<ToolNode.Parent>().map { it.name })
    }

    @Test
    fun `default provider failure degrades to empty defaults`() = runBlocking {
        val loaded = newRepo(default = null).load()
        assertNull(loaded.mainConfigError, "默认资源读取失败属于内置问题，不向用户报错")
        assertTrue(loaded.nodes.isEmpty(), "默认资源失败应降级空列表")
    }

    // ═══════════ 3.2 旧数据迁移（扁平结构 / 缺 id / 幂等 / .bak / 损坏降级） ═══════════

    @Test
    fun `legacy flat structure migrates into default group with backup and rewritten ids`() = runBlocking {
        val legacy = """[{"type":1,"name":"旧输入工具","body":{"type":2,"name":"旧输入工具","value":"echo {x}","list":[{"name":"x","desc":"参数x"}]},"showOutput":true}]"""
        customFile.writeText(legacy)

        val loaded = newRepo().load()
        val customParents = customParentIds(loaded.nodes)
        assertEquals(1, customParents.size, "旧扁平结构应归入唯一默认分组")
        val defaultGroup = customParents.single()
        assertEquals(ShellToolsConfigRepository.DEFAULT_GROUP_ID, defaultGroup.id)
        assertTrue(defaultGroup.isDefaultGroup)

        val child = defaultGroup.children.single() as ToolNode.Child
        assertTrue(child.id.isNotBlank(), "缺 id 工具应补 UUID")
        val body = child.body as ChildBody.Input
        assertEquals("echo {x}", body.value, "type=2 导入数据应完整保留（不退化为空 Normal）")

        // .bak 备份 = 迁移前原文
        val backup = java.io.File(tempDir, "shell_tools_custom.json.bak")
        assertTrue(backup.exists(), "首次迁移前应写 .bak 备份")
        assertEquals(legacy, backup.readText(), ".bak 应保留迁移前原始内容")

        // 回写后文件带 id
        val rewritten = customFile.readText()
        assertTrue(rewritten.contains("\"id\""), "迁移后应立即回写带 id 的新格式")
    }

    @Test
    fun `migration is idempotent on second load`() = runBlocking {
        customFile.writeText(
            """[{"type":1,"name":"旧工具","body":{"type":0,"name":"旧工具","value":"echo old","desc":""},"showOutput":true}]"""
        )
        val repo = newRepo()
        val first = customParentIds(repo.load().nodes).single().children.single() as ToolNode.Child
        val second = customParentIds(repo.load().nodes).single().children.single() as ToolNode.Child
        assertEquals(first.id, second.id, "二次读入 id 应保持不变（幂等）")

        // 再次制造缺 id 状态（模拟外部改动），.bak 保留最早内容不被覆盖
        val backup = java.io.File(tempDir, "shell_tools_custom.json.bak")
        val backupTextBefore = backup.readText()
        customFile.writeText(
            """[{"type":1,"name":"旧工具2","body":{"type":0,"name":"旧工具2","value":"echo old2","desc":""},"showOutput":true}]"""
        )
        repo.load()
        assertEquals(backupTextBefore, backup.readText(), ".bak 应保留最早一次迁移前内容（不覆盖）")
    }

    @Test
    fun `corrupted custom file degrades to empty and keeps original file`() = runBlocking {
        val corrupted = "broken not json ["
        customFile.writeText(corrupted)
        val loaded = newRepo().load()
        assertTrue(customParentIds(loaded.nodes).isEmpty(), "损坏自定义文件应降级空自定义列表")
        assertEquals(corrupted, customFile.readText(), "降级时应保留原文件不破坏")
    }

    @Test
    fun `blank custom file loads as empty without migration`() = runBlocking {
        customFile.writeText("")
        val loaded = newRepo().load()
        assertTrue(customParentIds(loaded.nodes).isEmpty())
        assertFalse(java.io.File(tempDir, "shell_tools_custom.json.bak").exists(), "空文件无需迁移不应产生 .bak")
    }

    // ═══════════ 3.3 写路径文件产物 ═══════════

    @Test
    fun `upsertTool creates tool inside target group with uuid id`() = runBlocking {
        val repo = newRepo()
        val groupId = assertNotNull(repo.createGroup("我的分组"))
        repo.upsertTool(tool("工具A"), groupId)

        val group = customParentIds(repo.load().nodes).single()
        assertEquals("我的分组", group.name)
        val child = group.children.single() as ToolNode.Child
        assertTrue(child.id.isNotBlank(), "新建工具应生成 UUID id")
        assertEquals("工具A", child.name)
    }

    @Test
    fun `upsertTool without group lands in default group`() = runBlocking {
        val repo = newRepo()
        repo.upsertTool(tool("无分组工具"))

        val defaultGroup = customParentIds(repo.load().nodes).single()
        assertEquals(ShellToolsConfigRepository.DEFAULT_GROUP_ID, defaultGroup.id)
        assertEquals(1, defaultGroup.children.size)
    }

    @Test
    fun `upsertTool with matching id replaces in place keeping group membership`() = runBlocking {
        val repo = newRepo()
        val groupId = assertNotNull(repo.createGroup("我的分组"))
        repo.upsertTool(tool("旧名字", id = "stable-id"), groupId)
        repo.upsertTool(tool("新名字", id = "stable-id"), ShellToolsConfigRepository.DEFAULT_GROUP_ID)

        val parents = customParentIds(repo.load().nodes)
        val group = parents.first { it.name == "我的分组" }
        assertEquals(1, group.children.size, "编辑应原地替换而非新建")
        assertEquals("新名字", (group.children.single() as ToolNode.Child).name)
        assertEquals("stable-id", (group.children.single() as ToolNode.Child).id, "id 不变（改名不重建）")
    }

    @Test
    fun `deleteTool removes only the target`() = runBlocking {
        val repo = newRepo()
        repo.upsertTool(tool("A", id = "id-a"))
        repo.upsertTool(tool("B", id = "id-b"))
        repo.deleteTool("id-a")

        val children = customParentIds(repo.load().nodes).single().children
        assertEquals(listOf("B"), children.map { it.name })
    }

    @Test
    fun `createGroup rejects duplicate names including visible default group name`() = runBlocking {
        val repo = newRepo()
        assertNotNull(repo.createGroup("分组一"))
        assertNull(repo.createGroup("分组一"), "重名分组应返回 null")
        // 无自定义数据时默认分组不可见，同名创建被放行（不产生 UI 冲突）
        assertNotNull(repo.createGroup(ShellToolsConfigRepository.DEFAULT_GROUP_NAME))
        // 默认分组可见（有工具）时，与默认分组同名应被拒绝
        val repo2 = newRepo()
        repo2.upsertTool(tool("散装工具"))
        assertNull(repo2.createGroup(ShellToolsConfigRepository.DEFAULT_GROUP_NAME), "与可见默认分组同名应返回 null")
    }

    @Test
    fun `renameGroup renames keeping tools and promotes virtual default group to explicit`() = runBlocking {
        val repo = newRepo()
        repo.upsertTool(tool("散落工具", id = "loose"))
        // 虚拟默认分组（未分组工具）改名 → 提升为显式分组落盘
        assertTrue(repo.renameGroup(ShellToolsConfigRepository.DEFAULT_GROUP_ID, "收藏夹"))

        val parents = customParentIds(repo.load().nodes)
        val renamed = parents.single()
        assertEquals(ShellToolsConfigRepository.DEFAULT_GROUP_ID, renamed.id, "id 保持 DEFAULT_GROUP_ID")
        assertEquals("收藏夹", renamed.name)
        assertEquals(1, renamed.children.size, "组内工具应保持不变")

        // 显式分组重命名保持工具
        val gid = assertNotNull(repo.createGroup("分组二"))
        repo.upsertTool(tool("工具X", id = "x"), gid)
        assertTrue(repo.renameGroup(gid, "分组二改名"))
        val after = customParentIds(repo.load().nodes)
        assertEquals("分组二改名", after.first { it.id == gid }.name)
        assertEquals(1, after.first { it.id == gid }.children.size)
    }

    @Test
    fun `renameGroup rejects taken names and missing groups`() = runBlocking {
        val repo = newRepo()
        val g1 = assertNotNull(repo.createGroup("分组一"))
        val g2 = assertNotNull(repo.createGroup("分组二"))
        assertFalse(repo.renameGroup(g1, "分组二"), "目标名被占用应返回 false")
        assertFalse(repo.renameGroup("custom-group:missing", "任意名"), "分组不存在应返回 false")
    }

    @Test
    fun `deleteGroup migrates tools to default group and protects default group`() = runBlocking {
        val repo = newRepo()
        val gid = assertNotNull(repo.createGroup("待删分组"))
        repo.upsertTool(tool("迁移工具", id = "m1"), gid)
        repo.upsertTool(tool("默认组工具", id = "d1"))

        assertTrue(repo.deleteGroup(gid))
        val parents = customParentIds(repo.load().nodes)
        assertEquals(1, parents.size, "删除后只剩默认分组")
        assertEquals(setOf("m1", "d1"), parents.single().children.map { (it as ToolNode.Child).id }.toSet(), "组内工具应迁移至默认分组且 id 不变")

        assertFalse(repo.deleteGroup(ShellToolsConfigRepository.DEFAULT_GROUP_ID), "默认分组删除应被拒绝")
        assertFalse(repo.deleteGroup("custom-group:missing"), "分组不存在应返回 false")
    }

    @Test
    fun `moveTool changes group membership keeping id and data`() = runBlocking {
        val repo = newRepo()
        val gid = assertNotNull(repo.createGroup("目标分组"))
        repo.upsertTool(tool("搬家工具", id = "move-me"))
        repo.upsertTool(tool("留守工具", id = "stay"))

        assertTrue(repo.moveTool("move-me", gid))
        val parents = customParentIds(repo.load().nodes)
        val target = parents.first { it.id == gid }
        assertEquals(1, target.children.size, "工具应移入目标分组")
        assertEquals("move-me", (target.children.single() as ToolNode.Child).id)
        val defaultGroup = parents.first { it.id == ShellToolsConfigRepository.DEFAULT_GROUP_ID }
        assertEquals(listOf("留守工具"), defaultGroup.children.map { (it as ToolNode.Child).name }, "原分组其他工具不受影响")

        assertFalse(repo.moveTool("nonexistent", gid), "工具不存在应返回 false")
    }

    @Test
    fun `export strips ids and reimport creates copies with new ids`() = runBlocking {
        val repo = newRepo()
        repo.upsertTool(tool("分享工具", id = "original-id"))
        val exported = repo.export(emptyList())
        assertFalse(exported.contains("original-id"), "导出应剥离 id（兼容旧分享物格式）")

        val count = repo.import(exported)
        assertEquals(1, count)
        val children = customParentIds(repo.load().nodes).single().children
        assertEquals(2, children.size, "导入是复制语义，原工具与新副本并存")
        val ids = children.map { (it as ToolNode.Child).id }
        assertEquals(2, ids.toSet().size, "副本应使用全新 id")
        assertEquals(1, ids.count { it == "original-id" }, "原工具恰好一个且保留原 id")
        assertEquals("分享工具", (children.first { (it as ToolNode.Child).id != "original-id" } as ToolNode.Child).name, "副本内容与原工具一致")
    }

    @Test
    fun `import accepts flat input-type fragment preserving type 2 body`() = runBlocking {
        val repo = newRepo()
        val fragment = """[{"type":1,"name":"输入工具","body":{"type":2,"name":"输入工具","value":"echo {p}","list":[{"name":"p","desc":"参数"}]},"showOutput":true}]"""
        assertEquals(1, repo.import(fragment))
        val child = customParentIds(repo.load().nodes).single().children.single() as ToolNode.Child
        val body = child.body as ChildBody.Input
        assertEquals("echo {p}", body.value, "导入 type=2 应完整保留（修复旧实现退化）")
        assertEquals(1, body.params.size)
    }

    @Test
    fun `import accepts group structure and mixed fragments`() = runBlocking {
        val repo = newRepo()
        val mixed = """
            [{"type":0,"name":"导入分组","body":[
                {"type":1,"name":"组内工具","body":{"type":3,"name":"组内工具","list":["cmd1","cmd2"]},"showOutput":true}
            ]},
            {"type":1,"name":"散装工具","body":{"type":0,"name":"散装工具","value":"echo loose","desc":""},"showOutput":true}]
        """.trimIndent()
        assertEquals(2, repo.import(mixed), "分组内工具 + 散装工具都应计入导入数")

        val parents = customParentIds(repo.load().nodes)
        val importedGroup = parents.first { it.name == "导入分组" }
        assertEquals(1, importedGroup.children.size)
        val chain = importedGroup.children.single().let { (it as ToolNode.Child).body as ChildBody.Chain }
        assertEquals(listOf("cmd1", "cmd2"), chain.commands)
        val defaultGroup = parents.first { it.id == ShellToolsConfigRepository.DEFAULT_GROUP_ID }
        assertEquals(listOf("散装工具"), defaultGroup.children.map { (it as ToolNode.Child).name })
    }

    @Test
    fun `import returns minus one for malformed json`() = runBlocking {
        assertEquals(-1, newRepo().import("broken ["))
        assertEquals(0, newRepo().import("   "))
    }

    // ═══════════ 撞 id 归一化（手编落盘文件重复 id：全唯一 / 命中正确对象 / 回写幂等 / 零撞不触碰） ═══════════

    /** 撞 id fixture：两组同 id、三工具同 id（组内两枚 + 散装一枚） */
    private val collidedCustomJson = """
        [
            {"type":0,"name":"分组甲","id":"custom-group:dup","body":[
                {"type":1,"name":"工具A","id":"dup-tool","body":{"type":0,"name":"工具A","value":"echo a","desc":""},"showOutput":true}
            ]},
            {"type":0,"name":"分组乙","id":"custom-group:dup","body":[
                {"type":1,"name":"工具B","id":"dup-tool","body":{"type":0,"name":"工具B","value":"echo b","desc":""},"showOutput":true}
            ]},
            {"type":1,"name":"散装工具","id":"dup-tool","body":{"type":0,"name":"散装工具","value":"echo c","desc":""},"showOutput":true}
        ]
    """.trimIndent()

    @Test
    fun `duplicate group and tool ids are normalized to globally unique on load`() = runBlocking {
        customFile.writeText(collidedCustomJson)
        val loaded = newRepo().load()

        // 断言面 1：载入后组/工具 id 全唯一，撞 id 项各归各位
        val parents = customParentIds(loaded.nodes)
        val namedGroups = parents.filter { !it.isDefaultGroup }
        assertEquals(2, namedGroups.size, "撞 id 分组应各自保留（首项保 id、次项重生成），不被吞并")
        val groupIds = parents.map { it.id }
        assertEquals(groupIds.size, groupIds.toSet().size, "分组 id 应全唯一")
        assertTrue("custom-group:dup" in namedGroups.map { it.id }, "首个撞 id 分组应保留原 id")
        val regeneratedGroup = namedGroups.first { it.id != "custom-group:dup" }
        assertEquals("分组乙", regeneratedGroup.name)
        assertTrue(
            regeneratedGroup.id.startsWith(ShellToolsConfigRepository.CUSTOM_GROUP_ID_PREFIX),
            "重生成分组 id 应带 custom-group 前缀",
        )

        val allToolIds = parents.flatMap { it.children }.map { (it as ToolNode.Child).id }
        assertEquals(3, allToolIds.size)
        assertEquals(3, allToolIds.toSet().size, "工具 id 应全唯一")
        assertEquals(1, allToolIds.count { it == "dup-tool" }, "首个撞 id 工具应保留原 id，其余各重生成")
    }

    @Test
    fun `duplicate default group id keeps first as default and regenerates the rest`() = runBlocking {
        customFile.writeText(
            """
            [
                {"type":0,"name":"显式默认","id":"custom","body":[
                    {"type":1,"name":"默认组工具","id":"t1","body":{"type":0,"name":"默认组工具","value":"echo d","desc":""},"showOutput":true}
                ]},
                {"type":0,"name":"假默认","id":"custom","body":[
                    {"type":1,"name":"假默认工具","id":"t2","body":{"type":0,"name":"假默认工具","value":"echo f","desc":""},"showOutput":true}
                ]}
            ]
            """.trimIndent()
        )
        val loaded = newRepo().load()
        val parents = customParentIds(loaded.nodes)

        // 默认分组语义豁免：首个 "custom" 占据默认分组，固定 id 恒不重生成
        val defaultGroup = parents.single { it.isDefaultGroup }
        assertEquals(ShellToolsConfigRepository.DEFAULT_GROUP_ID, defaultGroup.id, "默认分组 id 固定不重生成")
        assertEquals("显式默认", defaultGroup.name)

        // 次个撞 "custom" 的分组重生成带前缀 id，不再被展示层当作默认分组吞掉
        val other = parents.single { !it.isDefaultGroup }
        assertEquals("假默认", other.name)
        assertTrue(other.id.startsWith(ShellToolsConfigRepository.CUSTOM_GROUP_ID_PREFIX))
        assertEquals(listOf("假默认工具"), other.children.map { (it as ToolNode.Child).name }, "重生成分组的工具应随之归位")
    }

    @Test
    fun `rename and delete hit exact objects after collision normalization`() = runBlocking {
        customFile.writeText(collidedCustomJson)
        val repo = newRepo()
        repo.load() // 触发撞 id 归一化 + 立即回写

        // 断言面 2：改名删除各命中正确对象
        assertTrue(repo.renameGroup("custom-group:dup", "分组甲改名"))
        val afterRename = customParentIds(repo.load().nodes).filter { !it.isDefaultGroup }
        assertEquals("分组甲改名", afterRename.first { it.id == "custom-group:dup" }.name)
        assertEquals(
            listOf("分组乙"),
            afterRename.filter { it.id != "custom-group:dup" }.map { it.name },
            "改名应只命中保留原 id 的分组，重生成分组不受波及",
        )

        repo.deleteTool("dup-tool")
        val afterDeleteTool = customParentIds(repo.load().nodes).filter { !it.isDefaultGroup }
        assertEquals(
            listOf("工具B"),
            afterDeleteTool.first { it.name == "分组乙" }.children.map { (it as ToolNode.Child).name },
            "删工具应只命中保留原 id 的工具A，重生成的工具B 幸存",
        )

        assertTrue(repo.deleteGroup("custom-group:dup"))
        val afterDeleteGroup = customParentIds(repo.load().nodes).filter { !it.isDefaultGroup }
        assertEquals(listOf("分组乙"), afterDeleteGroup.map { it.name }, "删组应只命中保留原 id 的分组甲")
        assertEquals(
            listOf("工具B"),
            afterDeleteGroup.single().children.map { (it as ToolNode.Child).name },
            "重生成分组乙及其工具应完整幸存",
        )
    }

    @Test
    fun `collision rewrite is idempotent on second load`() = runBlocking {
        customFile.writeText(collidedCustomJson)
        val repo = newRepo()

        suspend fun snapshotIds(): List<Pair<String, String>> = customParentIds(repo.load().nodes).flatMap { parent ->
            listOf(parent.name to parent.id) + parent.children.map { (it as ToolNode.Child).name to it.id }
        }

        // 断言面 3：回写幂等——二次读入全部 id 不变（重生成只发生一次）
        val first = snapshotIds()
        val second = snapshotIds()
        assertEquals(first, second, "归一化立即回写后，二次读入 id 应不变（幂等）")
        assertEquals(6, first.size, "默认分组 + 分组甲 + 分组乙 各 1 分组条目 + 各 1 工具条目")

        // .bak 保留撞 id 原文（首次迁移前状态）
        val backup = java.io.File(tempDir, "shell_tools_custom.json.bak")
        assertEquals(collidedCustomJson, backup.readText(), ".bak 应保留撞 id 迁移前原文")
    }

    @Test
    fun `unique hand-written ids are preserved verbatim without rewrite`() = runBlocking {
        val content = """[{"type":0,"name":"我的分组","id":"custom-group:g1","body":[
            {"type":1,"name":"自定义工具","body":{"type":0,"name":"自定义工具","value":"echo custom","desc":""},"showOutput":true,"id":"u1"}
        ]}]"""
        customFile.writeText(content)
        val loaded = newRepo().load()

        // 断言面 4：零撞数据零触碰——归一化只对撞 id 项动手，正常数据 id 逐字保持
        val group = customParentIds(loaded.nodes).single()
        assertEquals("custom-group:g1", group.id, "非撞 id 应逐字保持")
        assertEquals("u1", (group.children.single() as ToolNode.Child).id, "非撞 id 应逐字保持")
        assertEquals(content, customFile.readText(), "零撞数据不应触发迁移回写（文件原样）")
        assertFalse(java.io.File(tempDir, "shell_tools_custom.json.bak").exists(), "零撞数据不应产生 .bak")
    }

    // ═══════════ 3.4 并发写不丢更新 + 主配置只读 ═══════════

    @Test
    fun `concurrent upserts do not lose updates`() = runBlocking {
        val repo = newRepo()
        val jobs = (1..20).map { i ->
            async { repo.upsertTool(tool("并发工具$i", id = "concurrent-$i")) }
        }
        jobs.awaitAll()

        val children = customParentIds(repo.load().nodes).single().children
        assertEquals(20, children.size, "并发 20 次写应全部落盘（Mutex 覆盖读-改-写全临界区）")
    }

    @Test
    fun `write operations never touch main config file`() = runBlocking {
        mainFile.writeText(mainText)
        val repo = newRepo()
        val before = mainFile.readText()
        val beforeMtime = mainFile.lastModified()

        val gid = repo.createGroup("分组")
        repo.upsertTool(tool("工具"), gid)
        repo.moveTool("nonexistent", gid ?: "custom")
        repo.renameGroup(gid ?: "custom", "改名")
        repo.deleteGroup(gid ?: "custom")
        repo.deleteTool("nonexistent")
        repo.import("""[{"type":1,"name":"导入","body":{"type":0,"name":"导入","value":"echo","desc":""},"showOutput":true}]""")
        repo.export(emptyList())

        assertEquals(before, mainFile.readText(), "任何写操作都不应修改主配置内容")
        assertEquals(beforeMtime, mainFile.lastModified(), "主配置 mtime 应保持不变（只读语义）")
    }
}
