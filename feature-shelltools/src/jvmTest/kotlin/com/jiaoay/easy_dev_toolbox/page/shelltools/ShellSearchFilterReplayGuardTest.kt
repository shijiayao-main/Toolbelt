package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 搜索过滤回放守护：fetch 重建节点后必须按非空 query 重放过滤。
 *
 * 历史缺陷面：进页/刷新钮/七类 CRUD 的 fetch 只回填 isExpanded 不回放 searchQuery
 * ——「框有词、树全量」自相矛盾态（空态文案与可见树冲突）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellSearchFilterReplayGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceOf(relativePath: String): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/$relativePath",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `fetch applies node transform and vm wires search filter`() {
        val controllerSrc = sourceOf("list/custom/CustomToolsController.kt")
        // 钩子声明 + fetch 消费（单点收口——进页/刷新/CRUD 全部 fetch 路径）
        assertTrue(
            "var nodeTransform: ((List<ToolNode>) -> List<ToolNode>)?" in controllerSrc,
            "过滤回放守护：controller 必须暴露 nodeTransform 钩子（fetch 后过滤重放的单点收口）",
        )
        assertTrue(
            "nodeTransform?.invoke(nodes)" in controllerSrc,
            "过滤回放守护：fetchShellToolsConfig 必须消费 nodeTransform（缺失 = CRUD/刷新后树全量回潮）",
        )

        val vmSrc = sourceOf("ShellToolsViewModel.kt")
        assertTrue(
            "customToolsController.nodeTransform = { nodes ->" in vmSrc,
            "过滤回放守护：VM 必须注入过滤变换钩子（与 onSearchQueryChange 滤网单源）",
        )
        assertTrue(
            "searchFilterTransform(nodes, _searchQuery.value)" in vmSrc,
            "过滤回放守护：钩子必须复用 searchFilterTransform 滤网（两处各写一份必漂移；" +
                "onSearchQueryChange 与钩子双写同理防漂移）",
        )
    }
}
