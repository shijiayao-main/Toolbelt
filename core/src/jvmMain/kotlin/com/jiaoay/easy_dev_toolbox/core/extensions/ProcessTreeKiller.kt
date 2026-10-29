package com.jiaoay.easy_dev_toolbox.core.extensions

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程树终止单源（shell 语义与 argv 语义共用）。
 *
 * 为什么需要树杀：`Process.destroy()` 只作用于**直接子进程**。shell 包装形态下
 * 直接子进程是 `zsh -l -c` / `cmd.exe /c` 解释器本身，真正干活的命令（`adb logcat`、
 * `gradle`、用户脚本）是孙进程——解释器一死，孙进程被 init 收养继续持有管道与端口，
 * 成为孤儿。Windows 尤其明显：无进程组概念，父进程终止不会级联。
 *
 * 契约：
 * - **先快照再终止**：边遍历边杀会让 `descendants()` 漏掉已退出节点的子孙；
 * - **叶子先于父**：父进程先死会让子进程被重挂，后续再也枚举不到；
 * - `force = false` 走 `destroy()`（Unix SIGTERM / Windows 终止请求），
 *   `force = true` 走 `destroyForcibly()`（SIGKILL / TerminateProcess）；
 * - 全程 `runCatching`——单个句柄终止失败（已退出、权限不足）不得阻断其余节点；
 * - 纯 JDK API（`ProcessHandle.descendants`），不依赖 `taskkill`/`kill` 等外部命令，
 *   因此不需要 shell 通道也不需要额外进程。
 */
object ProcessTreeKiller {

    /**
     * 终止 [process] 及其全部子孙。
     *
     * @return 实际发出终止信号的节点数（含主进程；0 = 主进程已退出且无残留子孙）。
     */
    fun destroyTree(process: Process, force: Boolean): Int {
        val snapshot = snapshotTree(process)
        if (snapshot.isEmpty()) return 0
        // 叶子优先：按深度逆序，避免父进程先死导致子进程失去关联
        val handles = snapshot.sortedByDescending { depthOf(it) }
        var signaled = 0
        for (handle in handles) {
            val ok = runCatching {
                if (force) handle.destroyForcibly() else handle.destroy()
            }.getOrDefault(false)
            if (ok) signaled++
        }
        return signaled
    }

    /**
     * 快照 [process] 的整棵树（主进程在最前，其后为子孙）。
     *
     * `descendants()` 在进程已退出时会抛 `IllegalStateException`，且并发场景下
     * 枚举中途可能出现已消失的句柄——两次独立 `runCatching` 收口，最坏退化为
     * 「只有主进程」。
     */
    private fun snapshotTree(process: Process): List<ProcessHandle> {
        val root = runCatching { process.toHandle() }.getOrNull()
        val descendants = runCatching(process::descendants).getOrNull()
            ?.let { stream -> runCatching { stream.toList() }.getOrNull() }
            .orEmpty()
        return (listOfNotNull(root) + descendants).distinctBy { it.pid() }
    }

    /**
     * 节点深度（主进程 = 0）：用于叶子优先排序。
     *
     * 深度按父链回溯计算，`parent()` 在父进程已退出时返回 empty——此时按当前可见
     * 层级计数（仍能保证子先于父，只是层级可能被压缩）。
     */
    private fun depthOf(handle: ProcessHandle): Int {
        var depth = 0
        var cursor: ProcessHandle? = runCatching { handle.parent().orElse(null) }.getOrNull()
        val visited = ConcurrentHashMap.newKeySet<Long>()
        while (cursor != null && visited.add(cursor.pid())) {
            depth++
            cursor = runCatching { cursor.parent().orElse(null) }.getOrNull()
        }
        return depth
    }
}
