package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * knowledge:// URI 前缀（蒸馏知识入库归属接缝单源）。
 *
 * 蒸馏知识入库归属写「knowledge://<repo>」URI 串进 space.filePaths
 */
const val KNOWLEDGE_URI_PREFIX = "knowledge://"

class SpaceStore(
    private val db: ToolbeltDb,
) {

    private val json = Json
    private val stringList = ListSerializer(String.serializer())

    /** space 归档态（持久化字符串枚举）。 */
    enum class SpaceStatus(val persisted: String) {
        ACTIVE("active"),
        ARCHIVED("archived"),
        ;

        companion object {
            /** 解码兜底：未知值落 ACTIVE（前向兼容）。 */
            fun decode(raw: String?): SpaceStatus =
                entries.firstOrNull { it.persisted == raw } ?: ACTIVE
        }
    }

    /** space 元数据行模型（UI 消费面——三列成员已解码为 id/路径列表）。 */
    data class SpaceSummary(
        val id: String,
        val title: String,
        val status: SpaceStatus,
        val filePaths: List<String>,
        val flowIds: List<String>,
        val runIds: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** 新建/覆写 space（同 id 幂等 upsert——insertSpace ON CONFLICT 对齐）。 */
    fun create(
        id: String,
        title: String,
        now: Long = System.currentTimeMillis(),
    ) {
        db.spacesQueries.insertSpace(
            id = id,
            title = title,
            status = SpaceStatus.ACTIVE.persisted,
            file_paths = encode(emptyList()),
            flow_ids = encode(emptyList()),
            run_ids = encode(emptyList()),
            created_at = now,
            updated_at = now,
        )
    }

    /** 全量 space（updated_at 倒序——空间栏列表消费面；[includeArchived] false 时滤归档）。 */
    fun listSpaces(includeArchived: Boolean = false): List<SpaceSummary> =
        db.spacesQueries.allSpaces().executeAsList()
            .map { it.toSummary() }
            .filter { includeArchived || it.status == SpaceStatus.ACTIVE }

    /** 单 space 查询（不存在返回 null）。 */
    fun spaceById(id: String): SpaceSummary? =
        db.spacesQueries.spaceById(id).executeAsOneOrNull()?.toSummary()

    /** 活跃触达（成员写入/切进空间后刷新列表排序）。 */
    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        db.spacesQueries.touchSpace(updatedAt = now, id = id)
    }

    /** 管理面重命名。 */
    fun rename(id: String, title: String, now: Long = System.currentTimeMillis()) {
        db.spacesQueries.renameSpace(title = title, updatedAt = now, id = id)
    }

    /** 归档/取消归档（结案收起不删除）。 */
    fun archive(id: String, archived: Boolean, now: Long = System.currentTimeMillis()) {
        val status = if (archived) SpaceStatus.ARCHIVED else SpaceStatus.ACTIVE
        db.spacesQueries.setStatus(status = status.persisted, updatedAt = now, id = id)
    }

    /** 追加成员文件路径（去重保序——重复 offer 幂等）。 */
    fun addFiles(id: String, paths: List<String>, now: Long = System.currentTimeMillis()) {
        mutate(id, now) { s -> s.copy(filePaths = (s.filePaths + paths).distinct()) }
    }

    /** 追加成员会话 id（去重保序）。 */
    fun addFlows(id: String, flowIds: List<String>, now: Long = System.currentTimeMillis()) {
        mutate(id, now) { s -> s.copy(flowIds = (s.flowIds + flowIds).distinct()) }
    }

    /** 追加成员 run id（去重保序）。 */
    fun addRuns(id: String, runIds: List<String>, now: Long = System.currentTimeMillis()) {
        mutate(id, now) { s -> s.copy(runIds = (s.runIds + runIds).distinct()) }
    }

    /** 移除单个成员（按类别；路径/id 不存在时幂等无操作）。 */
    fun removeMember(id: String, kind: MemberKind, value: String, now: Long = System.currentTimeMillis()) {
        mutate(id, now) { s ->
            when (kind) {
                MemberKind.FILE -> s.copy(filePaths = s.filePaths - value)
                MemberKind.FLOW -> s.copy(flowIds = s.flowIds - value)
                MemberKind.RUN -> s.copy(runIds = s.runIds - value)
            }
        }
    }

    /** 成员类别（removeMember 用）。 */
    enum class MemberKind { FILE, FLOW, RUN }

    fun delete(id: String) {
        db.spacesQueries.deleteSpace(id)
    }

    /** 行计数（迁移/空库验收用）。 */
    fun countSpaces(): Long = db.spacesQueries.countSpaces().executeAsOne()

    // ── 内部 ──

    /** 读-改-写收敛点（成员增删的唯一路径——编解码收口）。 */
    private fun mutate(id: String, now: Long, transform: (SpaceSummary) -> SpaceSummary) {
        val current = spaceById(id) ?: return
        val next = transform(current)
        db.spacesQueries.insertSpace(
            id = next.id,
            title = next.title,
            status = next.status.persisted,
            file_paths = encode(next.filePaths),
            flow_ids = encode(next.flowIds),
            run_ids = encode(next.runIds),
            created_at = next.createdAt,
            updated_at = now,
        )
    }

    private fun encode(values: List<String>): String =
        json.encodeToString(stringList, values)

    private fun decode(raw: String?): List<String> =
        runCatching { json.decodeFromString(stringList, raw ?: "[]") }.getOrDefault(emptyList())

    private fun Spaces.toSummary() = SpaceSummary(
        id = id,
        title = title,
        status = SpaceStatus.decode(status),
        filePaths = decode(file_paths),
        flowIds = decode(flow_ids),
        runIds = decode(run_ids),
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
