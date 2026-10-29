package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.material3.MaterialTheme
import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.toolbelt.device.ShellOutput
import com.jiaoay.toolbelt.device.ShellOutputSource
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.testfixtures.UserHomeIsolation
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.jiaoay.toolbelt.device.AdbPullGateway
import com.jiaoay.toolbelt.device.AdbPullHandle
import com.jiaoay.toolbelt.device.DeviceFileEntry
import com.jiaoay.toolbelt.device.DeviceFilePuller
import com.jiaoay.toolbelt.device.DeviceFileStat
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 文件子页拉回产物行 + 名称过滤框场景渲染像素带守护（[SceneGuardSupport] 单源
 * 配方，设备观测域面板场景守护同款）：状态注入走生产链——真实 [DeviceFilePuller]
 * + fake pull 网关（.part 落盘 → 校验 → 转正管线真跑通，零真子进程），产物登记
 * 走 VM 真链（pullEntry → 会话缓存）。本地目标一律临时目录（不碰用户数据根）。
 *
 * 验证面（产物行/过滤框回归网；产物行信息密度与多钮排列审美归维护者真机终裁）：
 * - 产物行在场：拉回产物会话列表行作为新增内容带进渲染面（文件/目录两形态同构
 *   ——各恰增一带，会话列表渲染链断裂即空壳红）；
 * - 多钮差分：文件产物行比目录产物行多「查看」+「送分析」钮（文件专属动作门，
 *   目录产物行恒无）——行带数一致下 primary 着色差分计数直证多钮（动作门倒挂
 *   即差分见底红）；
 * - 过滤投影差分：无命中过滤词把条目行簇（目录/symlink 行 primary 着色）清出渲染面
 *   ——primary 着色差分计数直证条目行清场（过滤框零投影/翻页不清词链断裂均红）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceFilePullProductSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-file-product-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    /** VM init/拉取动作挂起体（viewModelScope Main 档）的推进调度器 */
    private val dispatcher = StandardTestDispatcher()

    /** 设备串号（fake 设备源与拉取来源标识共用同一台） */
    private val serial = "SN-TEST-0001"

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
        Dispatchers.resetMain()
    }

    /** 目录列举 fixture（三条目：目录 / 文本文件 / symlink，:core 浏览器测试同基线形态） */
    private fun okEntries() = listOf(
        entry("Movies", DeviceFileType.DIRECTORY),
        entry("notes.txt"),
        entry("sdcard", DeviceFileType.SYMLINK),
    )

    private fun entry(name: String, type: DeviceFileType = DeviceFileType.FILE) = DeviceFileEntry(
        name = name,
        type = type,
        symlinkTarget = if (type == DeviceFileType.SYMLINK) "/storage/emulated/0" else null,
        sizeBytes = 512,
        permissions = "drwxrwx--x",
        owner = "root",
        group = "root",
        modifiedTime = "2026-10-11 14:23",
    )

    /** 设备域 fake 底座：adb 检测回单台设备（VM 目标串号解析锚定该台） */
    private fun deviceFakes(): DeviceMonitor {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = { Result.success(ShellResult(0, "", "")) },
            endpointReader = { AdbEndpoint("") },
        )
        monitor.applyDeviceList(listOf(DeviceInfo(serial, "Demo-1")))
        return monitor
    }

    /** fake pull 网关：记录命令，按脚本回执（脚本把产物写进 .part 路径并吐摘要行） */
    private class FakePullGateway(
        private val script: suspend (command: String, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPullGateway {
        val commands = mutableListOf<String>()

        override suspend fun execute(
            command: String,
            onStarted: (AdbPullHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            onStarted(object : AdbPullHandle {
                override fun kill() {}
            })
            return Result.success(script(command, onLine))
        }
    }

    /** 单文件拉取成功脚本：512 字节写 .part + 单文件摘要行（校验面 = stat 期望 512） */
    private val filePullScript: suspend (String, suspend (ShellOutput) -> Unit) -> Int =
        { command, onLine ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(512))
            onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    /** 目录递归拉取成功脚本：两文件写 .part 目录 + 多文件摘要行（目录链校验面弱化） */
    private val dirPullScript: suspend (String, suspend (ShellOutput) -> Unit) -> Int =
        { command, onLine ->
            val partDir = java.io.File(command.substringAfterLast(" ").trim('\'')).apply { mkdirs() }
            java.io.File(partDir, "a.alog").writeBytes(ByteArray(512))
            java.io.File(partDir, "sub").mkdirs()
            java.io.File(partDir, "sub/b.alog").writeBytes(ByteArray(100))
            onLine(ShellOutput("2 files pulled, 0 skipped. 38.4 MB/s (612 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    /** fake 设备侧 stat（拉取前置校验锚：文件链期望尺寸即本地落盘字节数） */
    private fun pullStat(size: Long): suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
        Result.success(
            DeviceFileStat(
                type = DeviceFileType.FILE,
                sizeBytes = size,
                modifiedEpochSeconds = 1_700_000_000L,
                permissions = "-rw-rw----",
                owner = "root",
                group = "root",
            ),
        )
    }

    /** 产物 VM（fake 列举内核直回条目 + fake pull 网关注入真拉取内核；本地目标 = 临时目录） */
    private fun productVm(
        devices: DeviceStateSource,
        script: suspend (String, suspend (ShellOutput) -> Unit) -> Int,
        statSize: Long,
    ): DeviceFileBrowserViewModel {
        val targetDir = Files.createTempDirectory("scene-pull").toFile()
        return DeviceFileBrowserViewModel(
            devices,
            listDirectory = { _, _ -> Result.success(okEntries()) },
            puller = DeviceFilePuller(
                runner = NoopRunner,
                gateway = FakePullGateway(script),
                statEntry = pullStat(statSize),
                endpointReader = { AdbEndpoint("") },
            ),
            pullTargetDir = targetDir,
        )
    }

    /** 单源渲染的 Main 档任务推进（onFrame 钩子实参）：装载/拉取回写逐帧收敛 */
    private fun pumpMainTasks() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `pull product rows render with file exclusive actions and filter collapses entries`() {
        TaskPipelineHandoff.clearForTest()
        TaskPipelineHandoff.wire {} // 衔接门接线（生产形态：文件产物行出「送分析」钮）
        try {
            val monitor = deviceFakes()
            val fileVm = productVm(monitor, filePullScript, statSize = 512)
            val dirVm = productVm(monitor, dirPullScript, statSize = 4096)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(fileVm.state.value.entries != null, "前置：文件 VM 条目装载")
            assertTrue(dirVm.state.value.entries != null, "前置：目录 VM 条目装载")

            // 基线帧：无产物会话行的面板形态（目录 VM 拉取前）
            val basePixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
                DeviceFileBrowserPanel(devices = monitor, sharedViewModel = dirVm)
            }
            val baseBands = contentRows(basePixels, SCENE_W)
            assertBandsNotOverlapping(baseBands)
            assertTrue(
                baseBands.size >= 6,
                "基线态应渲染 ≥6 内容带（头部/路径行/过滤框/三条目行），实际 ${baseBands.size}：$baseBands",
            )

            // 文件产物行：拉回产物会话列表行进渲染面（恰增一带）
            fileVm.pullEntry(entry("notes.txt"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, fileVm.state.value.pullProducts.size, "前置：文件产物登记进会话缓存")
            val filePixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
                DeviceFileBrowserPanel(devices = monitor, sharedViewModel = fileVm)
            }
            val fileBands = contentRows(filePixels, SCENE_W)
            assertBandsNotOverlapping(fileBands)
            assertEquals(
                baseBands.size + 1,
                fileBands.size,
                "文件产物行应恰增一条内容带（会话列表行），基线 ${baseBands.size} 带 → " +
                    "有产物 ${fileBands.size} 带——产物行未进渲染面或带了其他意外增减",
            )

            // 过滤投影差分：无命中过滤词把条目行簇（目录/symlink 行 primary 着色）清出渲染面
            fileVm.setQuery("zzzz")
            val filteredPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
                DeviceFileBrowserPanel(devices = monitor, sharedViewModel = fileVm)
            }
            val filteredBands = contentRows(filteredPixels, SCENE_W)
            assertBandsNotOverlapping(filteredBands)
            assertTrue(
                filteredBands != fileBands,
                "过滤前后渲染带分布应不同（内容确已变化，非状态自说自话）——" +
                    "过滤前 $fileBands / 过滤后 $filteredBands",
            )
            val primaryColor = probeSchemeColor { MaterialTheme.colorScheme.primary }
            assertTrue(
                countColor(filePixels, primaryColor) >=
                    countColor(filteredPixels, primaryColor) + ENTRY_ROWS_PRIMARY_LOSS_MIN,
                "过滤无命中后条目行簇应退出渲染面（目录/symlink 行的 primary 着色图标与名文本清场，" +
                    "差分 < $ENTRY_ROWS_PRIMARY_LOSS_MIN 即过滤投影链断裂）——" +
                    "未过滤 ${countColor(filePixels, primaryColor)} px / 已过滤 ${countColor(filteredPixels, primaryColor)} px",
            )

            // 目录产物行：与文件产物行同构（恰增一带），差异只在行内多钮
            dirVm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, dirVm.state.value.pullProducts.size, "前置：目录产物登记进会话缓存")
            val dirPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
                DeviceFileBrowserPanel(devices = monitor, sharedViewModel = dirVm)
            }
            val dirBands = contentRows(dirPixels, SCENE_W)
            assertBandsNotOverlapping(dirBands)
            assertEquals(
                baseBands.size + 1,
                dirBands.size,
                "目录产物行应恰增一条内容带（会话列表行），实际 ${dirBands.size} 带",
            )
            assertEquals(
                fileBands.size,
                dirBands.size,
                "文件/目录产物行带数应一致（行内多钮不增带）——差异只允许在着色差分",
            )

            // 多钮差分：文件产物行比目录产物行多「查看」+「送分析」钮（文件专属动作门）
            assertTrue(
                countColor(filePixels, primaryColor) >= countColor(dirPixels, primaryColor) + FILE_ONLY_ACTIONS_PRIMARY_GAIN_MIN,
                "文件产物行应多出文件专属钮（查看/送分析）的 primary 着色像素（差分 < " +
                    "$FILE_ONLY_ACTIONS_PRIMARY_GAIN_MIN 即动作门倒挂/多钮缺席）——" +
                    "文件行 ${countColor(filePixels, primaryColor)} px / 目录行 ${countColor(dirPixels, primaryColor)} px",
            )
        } finally {
            TaskPipelineHandoff.clearForTest()
        }
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 「查看」+「送分析」钮文本（primary 着色）的文件专属多钮差分计数下限 */
        private const val FILE_ONLY_ACTIONS_PRIMARY_GAIN_MIN = 10

        /** 目录/symlink 条目行（primary 着色图标 + 名文本）被过滤清场的差分计数下限 */
        private const val ENTRY_ROWS_PRIMARY_LOSS_MIN = 30
    }
}
