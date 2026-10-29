package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.DefaultConfigProvider
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ComposeDefaultConfigProvider
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolNodeConfig
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolsConfigLoader
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolsConfigParser
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.entity.JErrorCode
import com.jiaoay.easy_dev_toolbox.entity.JResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 三源配置统一管道（core 层唯一配置出入口）：
 *
 * 1. 内置默认配置（composeResources 打包）作为展示基线
 * 2. 用户主配置 `shell_tools.json` 存在且解析成功 → **完整取代**默认配置；
 *    不存在 → 静默使用默认（新用户开箱可用）；读失败/空白/解析失败 → 回退默认并随结果下发错误提示
 * 3. 自定义配置 `shell_tools_custom.json` 解析为分组树，整体追加在列表尾部
 *
 * 只读语义：主配置仅被 [load] 读取，本类全部写 API 只操作自定义文件（编译期即无主配置写路径）。
 * 并发安全：单一 [Mutex] 覆盖自定义配置"读-改-写"全临界区；页面级 [load] 的快速路径在锁外读
 * （原子写保证文件永远完整），迁移路径的读+回写整体持锁。
 *
 * 注意：Mutex 为实例级，生产环境必须共享单例（见 [CustomToolsController] 持有方式）。
 */
class ShellToolsConfigRepository(
    private val mainConfigFile: File = FileUtils.getShellToolsFile(),
    private val customConfigFile: File = FileUtils.getShellToolsCustomFile(),
    private val defaultConfigProvider: DefaultConfigProvider = ComposeDefaultConfigProvider,
) {

    companion object {
        private const val TAG = "ShellToolsConfigRepo"

        /** 默认自定义分组 id（沿用旧 CUSTOM_PARENT_ID，兼容展开状态记忆） */
        const val DEFAULT_GROUP_ID = "custom"

        const val DEFAULT_GROUP_NAME = "我的自定义"

        /** 显式自定义分组 id 前缀（与主配置路径式 id 天然不冲突） */
        const val CUSTOM_GROUP_ID_PREFIX = "custom-group:"

        private const val TYPE_PARENT = 0
        private const val BACKUP_SUFFIX = ".bak"
        private const val TMP_SUFFIX = ".tmp"
    }

    /** [load] 产物：三源合并后的节点树 + 主配置错误提示（成功回退默认时非空，供 UI 展示） */
    data class LoadedShellTools(
        val nodes: List<ToolNode>,
        val mainConfigError: String?,
    )

    /** 自定义分组的内存形态（落盘时编码为 type=0 节点） */
    private data class CustomGroup(
        val id: String,
        var name: String,
        val children: MutableList<ShellToolNodeConfig>,
    )

    /** 自定义配置的内存规范形态：显式分组 + 未分组工具（旧扁平结构/默认分组虚拟区） */
    private data class CustomData(
        val flatChildren: MutableList<ShellToolNodeConfig>,
        val groups: MutableList<CustomGroup>,
    )

    private data class CustomSnapshot(val data: CustomData, val needsMigration: Boolean)

    /** explicitNulls=false：导出剥离 id 后不输出 "id": null，保持旧分享物格式干净 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val configListSerializer = ListSerializer(ShellToolNodeConfig.serializer())

    private val mutex = Mutex()

    // ══════════════════ 读管道：默认资源 → 主配置 → 自定义 ══════════════════

    suspend fun load(): LoadedShellTools = withContext(Dispatchers.IO) {
        val defaults = loadDefaultNodes()
        val (mainNodes, mainError) = loadMainOrFallback(defaults)
        val customNodes = loadCustomNodes()
        LoadedShellTools(mainNodes + customNodes, mainError)
    }

    private suspend fun loadDefaultNodes(): List<ToolNode> {
        val text = defaultConfigProvider.read() ?: return emptyList()
        return runCatching { ShellToolsConfigParser.parse(text) }.getOrElse { e ->
            JLog.e(TAG) { "内置默认配置解析失败，降级空列表: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun loadMainOrFallback(defaults: List<ToolNode>): Pair<List<ToolNode>, String?> {
        val main = ShellToolsConfigLoader.load(mainConfigFile)
        return when (main) {
            is JResult.Success -> main.data to null

            is JResult.Failed -> when (main.code) {
                // 主配置文件不存在：全新用户，静默使用内置默认配置
                JErrorCode.ShellConfigNotFound -> defaults to null

                // 读失败 / 空白 / 解析失败：回退默认配置，错误信息随结果下发
                else -> {
                    val error = describeMainConfigError(main)
                    JLog.w(TAG) { "主配置加载失败(${main.code.name})，回退内置默认配置: ${main.msg}" }
                    defaults to error
                }
            }

            else -> defaults to null
        }
    }

    private fun describeMainConfigError(failed: JResult.Failed): String = when (failed.code) {
        JErrorCode.ShellConfigParseFailed -> "主配置解析失败（${failed.msg ?: "未知错误"}），已回退内置默认配置"
        JErrorCode.ShellConfigEmpty -> "主配置文件为空，已回退内置默认配置"
        else -> "主配置读取失败（${failed.msg ?: "未知错误"}），已回退内置默认配置"
    }

    // ══════════════════ 自定义配置读入（含旧数据迁移） ══════════════════

    /**
     * 读入自定义配置并合成展示节点树。
     *
     * 迁移语义：旧扁平结构（顶层 type=1）读入后归入默认分组（id 固定 [DEFAULT_GROUP_ID]，不落盘）；
     * 工具/分组缺 id 或撞 id 时补/重生成 UUID 并**立即原子回写**固化（首次迁移前写 .bak），
     * 保证再次读入 id 不变（幂等）。
     * 迁移异常时保留原文件、降级为空自定义列表。
     */
    private suspend fun loadCustomNodes(): List<ToolNode> {
        // 快速路径：锁外读文件（原子写保证文件永远完整）
        val snapshot = readCustomSnapshot()
        if (snapshot == null || !snapshot.needsMigration) {
            return snapshot?.let { buildCustomNodes(it.data) } ?: emptyList()
        }
        // 迁移路径：读 + 回写整体持锁，避免迁移回写与并发写竞态
        return mutex.withLock {
            val locked = readCustomSnapshot()
                ?: return@withLock emptyList()
            if (!locked.needsMigration) {
                return@withLock buildCustomNodes(locked.data)
            }
            try {
                writeBackupOnce()
                atomicWriteCustom(encodeCustom(locked.data))
                JLog.i(TAG) { "自定义配置 id 迁移完成并回写: ${customConfigFile.absolutePath}" }
            } catch (e: Exception) {
                JLog.e(TAG) { "迁移回写失败，保留原文件: ${e.message}" }
            }
            buildCustomNodes(locked.data)
        }
    }

    /** 读自定义文件 → 规范化内存形态；文件缺失/读取失败/解析失败返回 null（降级空） */
    private fun readCustomSnapshot(): CustomSnapshot? {
        if (!customConfigFile.exists()) return null
        val text = runCatching { customConfigFile.readText() }.getOrElse { e ->
            JLog.e(TAG) { "读取自定义配置失败，降级为空: ${e.message}" }
            return null
        }
        if (text.isBlank()) {
            return CustomSnapshot(CustomData(mutableListOf(), mutableListOf()), needsMigration = false)
        }
        val configs = runCatching {
            json.decodeFromString(configListSerializer, text)
        }.getOrElse { e ->
            JLog.e(TAG) { "自定义配置解析失败，保留原文件并降级为空: ${e.message}" }
            return null
        }
        return normalizeCustomData(configs)
    }

    /**
     * 规范化：type=0 → 分组、type=1 → 未分组工具；缺 id 补 UUID、撞 id 重生成 UUID，均标记迁移。
     *
     * 撞 id 归一化：撞 id 的唯一来源是手编/合并落盘文件（导入路径一律生成新 id）。
     * 消费面按 id 定位对象——改组名/删组取首个命中、删工具按 id 全删，撞 id 会使操作
     * 命中错误对象且返回成功（静默错行为），故组 id 与工具 id 各自在全文件命名空间内查重：
     * 按落盘顺序保留首个——首个 id 为 [DEFAULT_GROUP_ID] 的分组即默认分组，其固定 id
     * 恒不参与重生成；其余撞 id 项重生成（组用 [CUSTOM_GROUP_ID_PREFIX] + UUID，工具用 UUID）
     * 并置迁移标记，由 [loadCustomNodes] 的立即回写机制自愈落盘。
     *
     * 确定性：保留者与重生成的取舍均按落盘顺序确定；回写后全文件 id 唯一，
     * 同一文件二次读入不再触发重生成（幂等，对齐缺 id 迁移的既有契约）。
     */
    private fun normalizeCustomData(configs: List<ShellToolNodeConfig>): CustomSnapshot {
        var migrated = false
        val data = CustomData(mutableListOf(), mutableListOf())
        val seenGroupIds = mutableSetOf<String>()
        val seenToolIds = mutableSetOf<String>()
        for (config in configs) {
            if (config.type == TYPE_PARENT) {
                var groupId = config.id?.takeIf { it.isNotBlank() }
                    ?: (CUSTOM_GROUP_ID_PREFIX + UUID.randomUUID()).also { migrated = true }
                if (!seenGroupIds.add(groupId)) {
                    groupId = CUSTOM_GROUP_ID_PREFIX + UUID.randomUUID()
                    seenGroupIds.add(groupId)
                    migrated = true
                }
                val children = decodeChildConfigs(config.body).map { child ->
                    val (withId, changed) = normalizeToolId(child, seenToolIds)
                    if (changed) migrated = true
                    withId
                }
                data.groups.add(CustomGroup(groupId, config.name, children.toMutableList()))
            } else {
                val (child, changed) = normalizeToolId(config, seenToolIds)
                if (changed) migrated = true
                data.flatChildren.add(child)
            }
        }
        return CustomSnapshot(data, migrated)
    }

    /**
     * 工具 id 归一化：缺 id 补 UUID；与已收下 id 撞车时重生成 UUID（消费面按 id 全树定位，
     * 撞 id 会使改名/删除命中错误对象）。新生成 id 一律注册进 [seen]，保证同文件后续
     * 撞 id 项同样可检出。
     * upsert 路径以空 [seen] 调用——仅保留补空 id 语义，非空 id 原样放行
     * （编辑流的原地替换依赖既有 id 命中）。
     */
    private fun normalizeToolId(child: ShellToolNodeConfig, seen: MutableSet<String>): Pair<ShellToolNodeConfig, Boolean> {
        val id = child.id
        if (!id.isNullOrBlank() && seen.add(id)) return child to false
        val fresh = UUID.randomUUID().toString()
        seen.add(fresh)
        return child.copy(id = fresh) to true
    }

    private fun decodeChildConfigs(body: JsonElement?): List<ShellToolNodeConfig> {
        if (body == null) return emptyList()
        return runCatching {
            json.decodeFromJsonElement(configListSerializer, body)
        }.getOrDefault(emptyList())
    }

    /** 内存形态 → 落盘结构：显式分组（type=0）在前、未分组工具（type=1）在后 */
    private fun encodeCustom(data: CustomData): List<ShellToolNodeConfig> {
        val result = mutableListOf<ShellToolNodeConfig>()
        data.groups.forEach { group ->
            result.add(
                ShellToolNodeConfig(
                    type = TYPE_PARENT,
                    name = group.name,
                    body = json.encodeToJsonElement(configListSerializer, group.children.toList()),
                    id = group.id,
                )
            )
        }
        result.addAll(data.flatChildren)
        return result
    }

    /** 合成自定义展示树：默认分组（显式或虚拟）在前，其余显式分组按落盘顺序 */
    private fun buildCustomNodes(data: CustomData): List<ToolNode> {
        val result = mutableListOf<ToolNode>()
        val explicitDefault = data.groups.firstOrNull { it.id == DEFAULT_GROUP_ID }
        val defaultChildren = (explicitDefault?.children ?: emptyList()) + data.flatChildren
        if (defaultChildren.isNotEmpty() || explicitDefault != null) {
            result.add(
                ToolNode.Parent(
                    id = DEFAULT_GROUP_ID,
                    name = explicitDefault?.name ?: DEFAULT_GROUP_NAME,
                    children = defaultChildren.map { it.toChildNode() },
                    isExpanded = false,
                    isVisible = true,
                    isEditable = true,
                    isDefaultGroup = true,
                )
            )
        }
        data.groups.filter { it.id != DEFAULT_GROUP_ID }.forEach { group ->
            result.add(
                ToolNode.Parent(
                    id = group.id,
                    name = group.name,
                    children = group.children.map { it.toChildNode() },
                    isExpanded = false,
                    isVisible = true,
                    isEditable = true,
                    isDefaultGroup = false,
                )
            )
        }
        return result
    }

    private fun ShellToolNodeConfig.toChildNode(): ToolNode.Child = ToolNode.Child(
        id = id.orEmpty(), // normalize 之后必有 id，空串仅防御
        name = name,
        body = ShellToolsConfigParser.parseChildBody(body),
        isEditable = true,
    )

    // ══════════════════ 写路径（Mutex 覆盖读-改-写全临界区，只写自定义文件） ══════════════════

    /**
     * 新建或更新自定义工具。
     * - [node.id] 命中已有工具 → 原地替换（保持分组归属，改名不重建）
     * - 未命中 → 加入 [groupId] 分组；groupId 为 null/[DEFAULT_GROUP_ID] 时落默认分组（显式或未分组区）
     */
    suspend fun upsertTool(node: ShellToolNodeConfig, groupId: String? = null) = mutex.withLock {
        val data = readCustomDataOrEmpty()
        val normalized = normalizeToolId(node, mutableSetOf()).first

        var replaced = false
        val flatIndex = data.flatChildren.indexOfFirst { it.id == normalized.id }
        if (flatIndex >= 0) {
            data.flatChildren[flatIndex] = normalized
            replaced = true
        }
        if (!replaced) {
            outer@ for (group in data.groups) {
                val index = group.children.indexOfFirst { it.id == normalized.id }
                if (index >= 0) {
                    group.children[index] = normalized
                    replaced = true
                    break@outer
                }
            }
        }
        if (!replaced) {
            resolveTargetGroup(data, groupId)?.children?.add(normalized)
                ?: data.flatChildren.add(normalized)
        }
        atomicWriteCustom(encodeCustom(data))
    }

    /** 按 id 删除自定义工具（全树搜索），其他工具不受影响 */
    suspend fun deleteTool(id: String) = mutex.withLock {
        val data = readCustomDataOrEmpty()
        data.flatChildren.removeAll { it.id == id }
        data.groups.forEach { group -> group.children.removeAll { it.id == id } }
        atomicWriteCustom(encodeCustom(data))
    }

    /**
     * 新建自定义分组，返回新分组 id。
     * @return null 表示分组名已被占用（含默认分组名）
     */
    suspend fun createGroup(name: String): String? = mutex.withLock {
        val data = readCustomDataOrEmpty()
        if (isGroupNameTaken(data, name, excludingGroupId = null)) {
            return@withLock null
        }
        val id = CUSTOM_GROUP_ID_PREFIX + UUID.randomUUID()
        data.groups.add(CustomGroup(id, name, mutableListOf()))
        atomicWriteCustom(encodeCustom(data))
        id
    }

    /**
     * 重命名分组（组内工具及其 id 不变）。
     * 默认分组的改名会把未分组工具提升为显式分组落盘（id 保持 [DEFAULT_GROUP_ID]）。
     * @return false 表示分组不存在或新名已被占用
     */
    suspend fun renameGroup(id: String, newName: String): Boolean = mutex.withLock {
        val data = readCustomDataOrEmpty()
        if (isGroupNameTaken(data, newName, excludingGroupId = id)) {
            return@withLock false
        }
        if (id == DEFAULT_GROUP_ID) {
            val explicit = data.groups.firstOrNull { it.id == DEFAULT_GROUP_ID }
            if (explicit != null) {
                explicit.name = newName
            } else {
                // 虚拟默认分组落盘为显式分组（未分组工具随之归组）
                data.groups.add(0, CustomGroup(DEFAULT_GROUP_ID, newName, data.flatChildren.toMutableList()))
                data.flatChildren.clear()
            }
        } else {
            val group = data.groups.firstOrNull { it.id == id } ?: return@withLock false
            group.name = newName
        }
        atomicWriteCustom(encodeCustom(data))
        true
    }

    /**
     * 删除自定义分组；组内工具迁移至默认分组（显式默认分组或未分组区）而非删除。
     * @return false 表示分组不存在或为受保护的默认分组
     */
    suspend fun deleteGroup(id: String): Boolean = mutex.withLock {
        if (id == DEFAULT_GROUP_ID) {
            JLog.w(TAG) { "拒绝删除默认分组" }
            return@withLock false
        }
        val data = readCustomDataOrEmpty()
        val group = data.groups.firstOrNull { it.id == id } ?: return@withLock false
        val explicitDefault = data.groups.firstOrNull { it.id == DEFAULT_GROUP_ID }
        if (explicitDefault != null) {
            explicitDefault.children.addAll(group.children)
        } else {
            data.flatChildren.addAll(group.children)
        }
        data.groups.remove(group)
        atomicWriteCustom(encodeCustom(data))
        true
    }

    /**
     * 跨组移动工具（工具 id 与数据不变，仅分组归属变化）。
     * @param targetGroupId 目标分组 id；[DEFAULT_GROUP_ID] 表示默认分组（显式或未分组区）
     * @return false 表示工具不存在
     */
    suspend fun moveTool(toolId: String, targetGroupId: String): Boolean = mutex.withLock {
        val data = readCustomDataOrEmpty()
        var tool: ShellToolNodeConfig? = null
        val flatIndex = data.flatChildren.indexOfFirst { it.id == toolId }
        if (flatIndex >= 0) {
            tool = data.flatChildren.removeAt(flatIndex)
        } else {
            outer@ for (group in data.groups) {
                val index = group.children.indexOfFirst { it.id == toolId }
                if (index >= 0) {
                    tool = group.children.removeAt(index)
                    break@outer
                }
            }
        }
        val moved = tool ?: return@withLock false
        resolveTargetGroup(data, targetGroupId)?.children?.add(moved)
            ?: data.flatChildren.add(moved)
        atomicWriteCustom(encodeCustom(data))
        true
    }

    /**
     * 导入 JSON 片段（旧扁平子节点数组 或 新分组结构数组均可，可混合）。
     * 导入 = 复制语义：工具/分组一律生成新 id，避免与现有数据冲突；
     * 类型 0/1/2/3 经单一解析器完整保留（type 1/2 不再退化）。
     * @return 导入的工具数；-1 表示片段解析失败
     */
    suspend fun import(jsonText: String): Int = mutex.withLock {
        val text = jsonText.trim()
        if (text.isEmpty()) return@withLock 0
        val configs = runCatching {
            json.decodeFromString(configListSerializer, text)
        }.getOrElse { e ->
            JLog.e(TAG) { "导入片段解析失败: ${e.message}" }
            return@withLock -1
        }
        val data = readCustomDataOrEmpty()
        var importedTools = 0
        for (config in configs) {
            if (config.type == TYPE_PARENT) {
                val group = CustomGroup(
                    id = CUSTOM_GROUP_ID_PREFIX + UUID.randomUUID(),
                    name = config.name,
                    children = mutableListOf(),
                )
                decodeChildConfigs(config.body).forEach { child ->
                    group.children.add(child.copy(id = UUID.randomUUID().toString()))
                    importedTools++
                }
                data.groups.add(group)
            } else {
                data.flatChildren.add(config.copy(id = UUID.randomUUID().toString()))
                importedTools++
            }
        }
        atomicWriteCustom(encodeCustom(data))
        importedTools
    }

    /**
     * 导出自定义工具为旧扁平片段格式（子节点数组，剥离 id 保证与既有分享物互通）。
     * @param ids 为空导出全部工具，否则仅导出命中的工具
     */
    suspend fun export(ids: List<String>): String = withContext(Dispatchers.IO) {
        val data = readCustomDataOrEmpty()
        val all = collectAllTools(data)
        val selected = if (ids.isEmpty()) all else all.filter { ids.contains(it.id) }
        json.encodeToString(configListSerializer, selected.map { it.copy(id = null) })
    }

    // ══════════════════ 内部工具 ══════════════════

    /** 锁内使用：读自定义文件，异常/缺失时返回空形态 */
    private fun readCustomDataOrEmpty(): CustomData {
        return readCustomSnapshot()?.data ?: CustomData(mutableListOf(), mutableListOf())
    }

    private fun collectAllTools(data: CustomData): List<ShellToolNodeConfig> {
        return data.flatChildren.toList() + data.groups.flatMap { it.children.toList() }
    }

    /** groupId → 目标分组；null/默认分组 id → 显式默认分组（若有）否则 null（未分组区） */
    private fun resolveTargetGroup(data: CustomData, groupId: String?): CustomGroup? {
        if (groupId == null || groupId == DEFAULT_GROUP_ID) {
            return data.groups.firstOrNull { it.id == DEFAULT_GROUP_ID }
        }
        // 目标分组可能已被并发删除：防御性回落到默认分组区
        return data.groups.firstOrNull { it.id == groupId }
    }

    /** 分组名占用检查：显式分组同名（排除自身）或与可见默认分组名相同 */
    private fun isGroupNameTaken(data: CustomData, name: String, excludingGroupId: String?): Boolean {
        if (data.groups.any { it.name == name && it.id != excludingGroupId }) return true
        val defaultVisible = data.flatChildren.isNotEmpty() ||
            data.groups.any { it.id == DEFAULT_GROUP_ID }
        return defaultVisible && name == DEFAULT_GROUP_NAME && excludingGroupId != DEFAULT_GROUP_ID
    }

    /** 首次迁移前写 .bak（已存在则保留最早的迁移前状态，供回滚） */
    private fun writeBackupOnce() {
        val backup = File(customConfigFile.parentFile, customConfigFile.name + BACKUP_SUFFIX)
        if (backup.exists()) return
        if (customConfigFile.exists()) {
            customConfigFile.copyTo(backup, overwrite = false)
        }
    }

    /**
     * 原子写（对齐 :lark-bridge ConfigRepository.writeAtomic 配方）：
     * tmp → fsync → ATOMIC_MOVE+REPLACE_EXISTING；ATOMIC_MOVE 不支持时降级 REPLACE_EXISTING
     * （🔴 不得改用 delete+rename——Windows 上 delete 被占用（杀毒/索引）即失败，且 delete 与
     * rename 之间存在**崩溃窗口**：目标已删、tmp 未到，配置整个丢失）。
     * 失败删 tmp 保留旧文件（抛 [IOException] 由调用方处理）。
     */
    private fun atomicWriteCustom(configs: List<ShellToolNodeConfig>) {
        val tmp = File(customConfigFile.parentFile, customConfigFile.name + TMP_SUFFIX)
        try {
            tmp.writeText(json.encodeToString(configListSerializer, configs))
            // fsync：确保数据落盘再 move（断电一致性）
            java.nio.channels.FileChannel.open(tmp.toPath(), java.nio.file.StandardOpenOption.WRITE)
                .use { it.force(true) }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    customConfigFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
                // 降级：非原子替换（跨文件系统 / 平台不支持 ATOMIC_MOVE）
                java.nio.file.Files.move(
                    tmp.toPath(),
                    customConfigFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() } // 失败删 tmp，保留旧文件不动
            throw e
        }
    }
}
