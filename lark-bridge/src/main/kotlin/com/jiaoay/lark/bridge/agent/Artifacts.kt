// 产物回传：agent 最终回复末尾的「产物标记行」解析、剥离、沙箱校验与上传编排。
// 🔴 协议（与 Backends.artifactsPromptSuffix 注入文本配套，两处漂移即标记永不被识别、产物回传静默失效）：agent 在最终回复最末尾每行一条 `[产物] <绝对路径>`；
//    只认「末尾连续块」的行首前缀——正文中间出现的 `[产物]` 字样不剥离、不解析（防误吞正文）。
// 🔴 失败模式安全：agent 不写标记行 ⇒ 零上传零副作用；任何校验/上传失败不阻断文字回复（失败条目标记行原样还原）。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.exec.SensitiveFilePolicy
import com.jiaoay.lark.bridge.exec.WorkspacePolicy
import java.io.File

/**
 * 产物标记行解析（纯函数，零 IO）。
 *
 * 解析规则：从文本**末尾向前**逐行扫描，收集以 `[产物] ` 开头的行；遇到首个非标记行即停
 * （正文中间的 `[产物]` 字样不构成标记块——防 code block / 引文误伤）。行内路径为空白的条目跳过。
 */
internal object ArtifactMarkers {

    /** 标记行前缀（与注入约定逐字一致）。 */
    const val PREFIX = "[产物] "

    /** 行是否为标记行（容忍 `[产物]` 无尾空格 / 只余空白的残缺形态——解析为空路径跳过）。 */
    private fun isMarkerLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith(PREFIX) || trimmed == PREFIX.trim()
    }

    /**
     * 从 [text] 尾部提取产物标记块。
     * @return body = 剥离标记块后的正文（去尾空行）；paths = 标记行携带的原始路径（未展开、未校验，按出现序）。
     *         无标记块时 body == text、paths 为空。
     */
    fun extract(text: String): Extracted {
        val lines = text.split("\n")
        // 从尾部向前：先越过任意空行（标记块与正文之间允许空行），随后必须命中至少一条标记行才构成块
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        if (end == 0 || !isMarkerLine(lines[end - 1])) {
            return Extracted(text, emptyList()) // 尾部（空行之前）不是标记行 ⇒ 无块
        }
        var start = end
        while (start > 0 && isMarkerLine(lines[start - 1])) start--
        val body = lines.subList(0, start).joinToString("\n").trimEnd()
        val paths = lines.subList(start, end)
            .map { it.trim().removePrefix(PREFIX.trim()).trim() }
            .filter { it.isNotEmpty() }
        return Extracted(body, paths)
    }

    /** [extract] 的结果：剥离后的正文 + 标记行路径清单。 */
    data class Extracted(val body: String, val paths: List<String>)
}

/**
 * 单个产物条目的校验与上传结果。
 * @param path 原始标记行路径（还原正文时逐字使用）
 * @param fileName 实际采用的文件名
 */
internal data class ArtifactOutcome(val path: String, val fileName: String, val uploaded: Boolean)

/**
 * 产物交付编排：解析 → 校验（数量/沙箱围栏/存在/敏感/大小/扩展名）→ 归档/超阈值 HIGH 确认卡 →
 * 上传 → 生成降级正文。
 *
 * 🔴 路径来自 agent 文本，**绝不信任**：必须通过 [WorkspacePolicy] canonical 沙箱校验
 *    （空 roots fail-safe 拒绝所有；符号链接穿越由 canonicalPath 防御）。
 * 判据序与 `send_file` 工具的交付围栏（outboundDeliveryFence，:feature-larkbridge）镜像：
 * 围栏前置、存在性后置，围栏外统一回 [SensitiveFilePolicy.OPAQUE_DENIED_HINT]——
 * 围栏外若回显区分性原因，间接注入的 LLM 可把标记链当任意绝对路径的存在性探测器。
 * 归档/打包扩展名（tar/zip/7z/gz 族）或体积超 [LARGE_ARTIFACT_CONFIRM_BYTES] 的产物
 * 上传前强制 HIGH 确认卡（卡面完整文件名+大小+路径）——散文件产物维持总开关语义不加卡。
 * 🔴 调用方（runAgentPipeline）须以 catch Throwable 包裹本函数且**显式 rethrow CancellationException**
 *    （E-1 纪律，与 Admission.deliver 同款）——业务异常按「原文照发」降级，取消信号必须上抛。
 *
 * @param reply 出站出口（file() 内部经 RateGovernor；上传失败返回非 ok SendResult）
 * @param policy 工作区沙箱
 * @param config 产物回传配置（enabled 已由调用方判定；maxCount/maxSizeBytes/allowedExtensions 在此消费）
 * @param homeDataRoot 桥接数据根（敏感文件判据 1 的输入；null = 未知，跳过该判据）。
 *   🔴 本通道与 `send_file` 工具**共用同一 [WorkspacePolicy]**，
 *      装闸必须**两条都装**——只给工具装、标记链不装，等于给同宽的旁路留了门
 *      （`_summary` 完全由 LLM 控制，注入后同样能诱导出 `[产物] <数据根内文件>` 标记行）。
 */
internal suspend fun deliverArtifacts(
    text: String,
    config: ArtifactsConfigView,
    policy: WorkspacePolicy,
    reply: ReplyFileSink,
    homeDataRoot: File? = null,
    /**
     * 归档/超阈值产物确认门（HIGH 卡：卡面完整文件名+大小+路径，防替答/超时=拒绝由
     * [ToolConfirmGate] 既有语义承载）。null = 确认链不可用 ⇒ 归档/超阈值条目
     * fail-closed 拒发（散文件产物不受影响，维持总开关语义）。
     */
    confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
): DeliveryResult {
    val extracted = ArtifactMarkers.extract(text)
    if (extracted.paths.isEmpty()) return DeliveryResult(body = text, artifacts = emptyList())

    var remainingSlots = config.maxCount
    val uploaded = mutableListOf<ArtifactOutcome>()
    val rejected = mutableListOf<String>() // 校验失败、还原为标记行

    for (raw in extracted.paths) {
        val expanded = com.jiaoay.lark.bridge.exec.Paths.expandTilde(raw)
        val file = File(expanded)
        val reject: (String) -> Unit = { reason ->
            rejected.add(ArtifactMarkers.PREFIX + raw + "  <!-- " + reason + " -->")
        }
        // requireInsideWorkspace 对不可解析路径抛 IOException ⇒ runCatching 按
        //    拒绝处理（与 `send_file` 工具的交付围栏同款）；空 roots fail-safe 恒 false。
        val insideSandbox = runCatching { policy.requireInsideWorkspace(file) }.getOrDefault(false)
        when {
            remainingSlots <= 0 -> reject("超出数量上限 ${config.maxCount}")
            // 围栏外统一 OPAQUE 文案：回显「文件不存在/不在沙箱」等区分性原因会让
            //    间接注入的 LLM 把标记链当任意绝对路径的存在性探测器——存在性判定必须后置。
            !insideSandbox -> reject(SensitiveFilePolicy.OPAQUE_DENIED_HINT)
            !file.isFile -> reject("文件不存在")
            // 🔴 敏感/凭据排除表：与 `send_file` 工具的同一张表、同一判据、同一拒绝文案。
            //    位置紧跟围栏——沙箱只答「是否在用户自配的工作区内」，凭据族文件
            //    （工作区配成家目录时的 ~/.ssh/id_rsa、~/.aws/credentials）通过了沙箱仍不该外发。
            SensitiveFilePolicy.isForbidden(file, homeDataRoot) -> reject(SensitiveFilePolicy.DENIED_HINT)
            file.length() > config.maxSizeBytes -> reject("超出大小上限 ${config.maxSizeBytes / 1048576}MB")
            !config.isExtensionAllowed(file.name) -> reject("扩展名不在白名单")
            else -> {
                // 归档/超阈值产物强制 HIGH 确认卡（完整文件名+大小+路径；卡后置到廉价校验之后，
                // 不为围栏外/敏感/超限条目空挂一次人工确认）。
                val confirmDenial = archiveConfirmDenial(file, confirmGate)
                if (confirmDenial != null) {
                    reject(confirmDenial)
                } else {
                    val sendResult = reply.file(file, file.name)
                    if (sendResult.ok) {
                        remainingSlots--
                        uploaded += ArtifactOutcome(path = raw, fileName = file.name, uploaded = true)
                    } else {
                        reject("上传失败 ${sendResult.error ?: ""}".trim())
                    }
                }
            }
        }
    }

    // 正文 = 剥离标记后的干净正文；有失败条目时把标记行（带原因注释）还原到末尾
    val body = buildString {
        append(extracted.body)
        if (rejected.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("以下产物未能回传：\n")
            append(rejected.joinToString("\n"))
        }
    }
    return DeliveryResult(body = body, artifacts = uploaded, rejected = rejected)
}

/**
 * 归档/超阈值产物的确认卡判据：免卡形态返回 null；需卡且未获批准返回拒绝理由
 * （链不可用 fail-closed / 用户拒绝 / 超时同文案——出站面不给区分性探测信息）。
 */
private suspend fun archiveConfirmDenial(
    file: File,
    confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate?,
): String? {
    if (!needsArchiveConfirmation(file.name, file.length())) return null
    val gate = confirmGate
        ?: return "归档/超阈值产物需逐个确认，但确认链不可用（fail-closed 拒发；散文件产物不受影响）"
    val verdict = gate.requireConfirmation(
        toolName = "[产物] 归档产物出站确认",
        inputSummary = "文件 ${file.name}（${file.length()}B）路径 ${file.path}",
        riskLevel = com.jiaoay.lark.bridge.confirm.RiskClassifier.RiskLevel.HIGH.name,
    )
    return if (verdict == com.jiaoay.lark.bridge.confirm.ToolConfirmGate.Verdict.APPROVED) {
        null
    } else {
        "归档/超阈值产物未获用户确认（拒绝/超时），拒发"
    }
}

/** [deliverArtifacts] 的结果：最终正文 + 上传成功的产物清单（写审计 attachments 用）+ 被拒条目清单（写审计 rejectedArtifacts）。 */
internal data class DeliveryResult(
    val body: String,
    val artifacts: List<ArtifactOutcome>,
    val rejected: List<String> = emptyList(),
)

/**
 * 归档/打包扩展名（出站前强制 HIGH 确认卡——单跳批量外发链的最短形态：
 * 一次 `tar czf` 整仓打包 + 一条标记行整仓出机器；散文件产物维持总开关语义不加卡）。
 * tar 族覆盖 gz/bz2/xz 三种压缩尾（.tar.gz/.tgz/.tar.bz2/.tbz2/.tar.xz/.txz）。
 */
internal val ARCHIVE_ARTIFACT_EXTENSIONS = listOf(
    ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz",
    ".zip", ".7z", ".gz",
)

/**
 * 单产物体积确认阈值（字节）：归档扩展名之外，超过本阈值的散文件产物同样强制
 * HIGH 确认卡（批量数据面——文本报告类产物通常 <1MB，超 5MB 即数据/日志整段外发形态）。
 * 值只许降不许升（升 = 放宽批量外发确认面）。
 */
internal const val LARGE_ARTIFACT_CONFIRM_BYTES: Long = 5L * 1024 * 1024

/** 产物是否命中归档/超阈值确认形态（HIGH 确认卡判据——文件名与大小双判） */
internal fun needsArchiveConfirmation(fileName: String, sizeBytes: Long): Boolean {
    val lower = fileName.lowercase()
    if (sizeBytes > LARGE_ARTIFACT_CONFIRM_BYTES) return true
    return ARCHIVE_ARTIFACT_EXTENSIONS.any { lower.endsWith(it) }
}

/** 产物回传配置的窄视图（由 BridgeConfig.ArtifactsConfig 提供；测试可直接构造）。 */
internal interface ArtifactsConfigView {
    val maxCount: Int
    val maxSizeBytes: Long
    val allowedExtensions: String
    fun isExtensionAllowed(fileName: String): Boolean {
        val list = allowedExtensions.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return true // 空 = 不限
        val lower = fileName.lowercase()
        // 🔴 无点条目自动补点（"md" → ".md"）：裸 endsWith 后缀语义会把 "md" 误匹配到 "cmd"；
        //    补点后收窄为扩展名语义，且不破坏任何带点写法（"tar.gz" → ".tar.gz"）。
        return list.any { ext -> lower.endsWith(if (ext.startsWith(".")) ext else ".$ext") }
    }
}

/** [deliverArtifacts] 需要的出站能力（ReplySink.file 的窄接口；测试用 fake）。 */
internal interface ReplyFileSink {
    suspend fun file(file: File, displayName: String?): com.jiaoay.lark.gateway.SendResult
}
