// S0 · 出站唯一出口守护：`MessageGateway.send(`/`openCardStream(` 的生产调用点必须收口在 ReplySink。
// 🔴 本守护的目标是「把现状固定住」：ReplySink.kt:1-4 KDoc 自称「唯一出站出口 ⇒ 一律经 RateGovernor
//    （NF-05）」，但实测存在三处历史旁路（Bridge.sendTest / BridgeAssembler 确认卡 / CLI SendCommand）。
//    它们是**已登记的已知旁路**，每条在白名单里带一行理由；第四处旁路从今天起会被本守护拦下。
// 🔴 扫描范围只含 `src/main/kotlin`（生产交付物），测试源集里的 Fake/Synthetic 网关构造不属于守护面
//    ——先例：LarkSdkIsolationTest E/F 组、BridgeAssemblerParityTest 均只扫生产源集。
// 🔴 验收纪律：扫盘型守护会被 build cache 假绿（AGENTS.md「测试」节），验收一律
//    `--rerun-tasks --no-build-cache`，且新守护落地按红绿双向验证（先故意造一处旁路确认红，再改回）。
package com.jiaoay.lark.bridge.pipeline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ReplySinkUniquenessTest {

    /**
     * 全部生产源（`lark-bridge/src/main` + `lark-gateway/src/main`）中，`gateway.send(` /
     * `gateway.openCardStream(` 的调用点必须 ⊆ `{ReplySink.kt}` ∪ [BYPASS_ALLOWLIST]。
     *
     * - 只匹配接收者名为 `gateway` 的调用（本仓 MessageGateway 值参数的既定命名约定；
     *   `lark-gateway` 内部 `ch.send(`/`client.send(` 是 SDK/HTTP 实现细节，不在出站契约面）；
     * - 用 [stripCommentsAndStrings] 归一化源再做匹配：ReplySink/Bridge 的 KDoc 里提及
     *   `gateway.send` 的文字（如 SdkLarkGateway.kt:290 的 🔴 P0 修复注释）不得造成假红；
     * - 白名单条目必须在归一化源里**仍有真实命中**（防腐化：删了旁路代码却留着白名单条目 ⇒ 红）。
     */
    @Test
    fun `A gateway send and openCardStream call sites are confined to ReplySink or registered bypasses`() {
        val root = testRepoRoot()
        val mainSources = listOf(
            File(root, "lark-bridge/src/main/kotlin"),
            File(root, "lark-gateway/src/main/kotlin"),
        )
        val ktFiles = mainSources
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
        // 空/缩水扫描 = 永绿后门（先例：LarkSdkIsolationTest B-1 的 MIN_KT_FILES 自检）
        check(ktFiles.size >= MIN_MAIN_KT_FILES) {
            "生产源扫描文件数异常（${ktFiles.size} < $MIN_MAIN_KT_FILES 下限）：目录定位可能失效，守护会静默失效"
        }

        val callSiteRegex = Regex("""\bgateway\s*\.\s*(send|openCardStream)\s*\(""")
        val violations = mutableListOf<String>()
        val hits = mutableSetOf<String>()
        for (file in ktFiles) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val normalized = stripCommentsAndStrings(file.readText())
            normalized.split('\n').forEachIndexed { idx, line ->
                if (!callSiteRegex.containsMatchIn(line)) return@forEachIndexed
                hits += rel
                val exempt = rel == REPLY_SINK_PATH || BYPASS_ALLOWLIST.any { (path, _) -> rel == path }
                if (!exempt) violations += "$rel:${idx + 1}: ${line.trim()}"
            }
        }

        // 防腐化断言：白名单每条必须仍有真实命中（旁路被重构掉 ⇒ 条目必须同步删除）
        val stale = BYPASS_ALLOWLIST.filter { (path, _) -> path !in hits }
        if (stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("BYPASS_ALLOWLIST 存在已无真实命中的条目（防腐化断言）：")
                    stale.forEach { (path, reason) -> appendLine("  - $path（理由：$reason）") }
                    appendLine("请删除该白名单条目——已消失的旁路不需要豁免。")
                },
            )
        }

        if (REPLY_SINK_PATH !in hits) {
            fail(
                "扫描未在 $REPLY_SINK_PATH 发现任何 gateway.send/openCardStream 调用——" +
                    "出口文件未被扫到或其调用已改名，本守护已失效，须同步修正 REPLY_SINK_PATH/匹配正则",
            )
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("出站旁路违规：MessageGateway.send/openCardStream 出现了未登记的调用点 ${violations.size} 处：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("所有出站必须经 ReplySink（NF-05：RateGovernor 放行后才发起网关调用）。")
                    appendLine("已知旁路须在 BYPASS_ALLOWLIST 登记并附理由；新增第四处旁路前先评估能否经 ReplySink 走。")
                },
            )
        }
    }

    private companion object {
        /** lark-bridge(91) + lark-gateway(17) 生产源 .kt 下限（实测 108，留余量） */
        const val MIN_MAIN_KT_FILES = 90

        /** 唯一合法调用点本体（不进 BYPASS_ALLOWLIST：它不是旁路，是出口） */
        const val REPLY_SINK_PATH =
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySink.kt"

        /** 自检：ReplySink.kt 必须真的被扫到（否则本守护退化为不扫出口，无人察觉） */
        /**
         * 已登记的出站旁路（路径 = 相对仓库根的生产文件路径）。🔴 每条必须带理由：
         * 1. `Bridge.sendTest`：门面连通性自检——用户主动触发的一次性探针，非消息回合，
         *    不应经频控（若频控生效，「发测试消息」会被自己拦住）。
         * 2. `BridgeAssembler` 确认卡：ConfirmRequest 的确认卡在准入前发出（尚无 handler 上下文，
         *    无 ReplySink 可用——ReplySink 每消息一个实例，绑定消息回合）。
         * 3. `cli/SendCommand`：CLI 直发命令——单进程一次性行为，无五阶段管线参与。
         */
        val BYPASS_ALLOWLIST: List<Pair<String, String>> = listOf(
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/Bridge.kt" to
                "sendTest 连通性自检（用户主动探针，不经频控）",
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeAssembler.kt" to
                "确认卡：准入前发出、无消息回合上下文",
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/SendCommand.kt" to
                "CLI 直发命令（单进程一次性行为，无管线）",
        )
    }
}
