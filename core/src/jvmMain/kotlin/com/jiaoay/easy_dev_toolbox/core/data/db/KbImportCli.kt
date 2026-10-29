package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import java.io.PrintStream

/**
 * kb-import CLI：md 知识包 → 解析校验 → KnowledgePack JSON → SQLite 四表（目标库 `~/.toolbelt/`）。
 *
 * ## 为什么 CLI 壳在 :core
 * [KnowledgeMdImporter] 依赖 SQLDelight 生成类与 kotlinx.serialization，全在 :core；
 * `:log-tools`（jvmTarget 11 纯 JVM）不能依赖 :core，拆下去会拖整套 DB 栈。
 *
 * ## 契约
 * argv 解析手写、不引第三方 CLI 库：本入口的消费面是 `./gradlew :core:runKbImport` 与
 * 开发机 `java -cp`，而 :core 是被最多模块依赖的基础层——为一个批处理入口把所有下游模块的
 * 编译类路径拖上 CLI 框架不划算。
 *
 * 选项：`--kb <目录>`（必选）、`--pack-out <文件>`、`--dry-run`、`-h/--help`。
 * `--opt=<值>` 与 `--opt <值>` 两种写法等价；任何未识别参数按用法错误处理。
 *
 * 退出码（脚本化消费据此判定）：0 = 成功（含 `--help`）；1 = 用法错误或解析校验失败。
 */
object KbImportCli {

    private const val EXIT_OK = 0
    private const val EXIT_FAILURE = 1

    private val USAGE = """
        |用法: kb-import --kb <目录> [--pack-out <文件>] [--dry-run]
        |
        |  --kb <目录>        知识包根目录（含 TAG_REGISTRY.md 与 <repo>/ 分节），必选
        |  --pack-out <文件>  同时输出 KnowledgePack JSON 到该路径（交换格式留档）
        |  --dry-run          只解析校验，不写库
        |  -h, --help         显示本帮助
    """.trimMargin()

    /** 解析产物：执行参数；用法错误经 [UsageError] 抛出。 */
    private data class Options(val kbRoot: File, val packOut: File?, val dryRun: Boolean)

    private class UsageError(message: String) : Exception(message)

    /** 执行一次 CLI 调用并返回进程退出码；不调用 `exitProcess`（宿主决定是否退出）。
     *
     * @param out 标准输出（用法/进度/统计）
     * @param err 标准错误（用法错误与校验失败摘要——消费方按「stderr 非空即失败」判定）
     */
    fun run(
        args: Array<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int {
        // help 先于一切取值校验：`--kb <坏路径> --help` 的语义是「要帮助」而不是用法错误
        if (args.any { it == "-h" || it == "--help" }) {
            out.println(USAGE)
            return EXIT_OK
        }
        val parsed = try {
            parseArgs(args)
        } catch (e: UsageError) {
            err.println("kb-import: ${e.message}")
            err.println()
            err.println(USAGE)
            return EXIT_FAILURE
        }
        return execute(parsed, out, err)
    }

    private fun parseArgs(args: Array<String>): Options {
        var kbRoot: File? = null
        var packOut: File? = null
        var dryRun = false
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--dry-run" -> dryRun = true
                arg == "--kb" -> kbRoot = kbRoot(requireValue(args, index++, "--kb"))
                arg.startsWith("--kb=") -> kbRoot = kbRoot(arg.substringAfter('='))
                arg == "--pack-out" -> packOut = packOut(requireValue(args, index++, "--pack-out"))
                arg.startsWith("--pack-out=") -> packOut = packOut(arg.substringAfter('='))
                else -> throw UsageError("未知参数：$arg")
            }
            index++
        }
        val root = kbRoot ?: throw UsageError("缺少必选参数 --kb <目录>")
        return Options(root, packOut, dryRun)
    }

    /** 取 `--opt value` 形态的取值；返回后调用方自增 index 即越过该值。
     * 以 `--` 开头的下一个 token 视为下一个选项而非取值（`--kb --dry-run` 是漏值不是路径）。
     */
    private fun requireValue(args: Array<String>, valueIndex: Int, option: String): String {
        val value = args.getOrNull(valueIndex + 1)
        if (value == null || value.startsWith("--")) {
            throw UsageError("选项 $option 缺少取值")
        }
        return value
    }

    private fun kbRoot(raw: String): File {
        if (raw.isEmpty()) throw UsageError("选项 --kb 的取值不能为空")
        val dir = File(raw)
        if (!dir.exists()) throw UsageError("--kb 目录不存在：${dir.path}")
        return dir
    }

    private fun packOut(raw: String): File {
        if (raw.isEmpty()) throw UsageError("选项 --pack-out 的取值不能为空")
        val file = File(raw)
        if (file.isDirectory) throw UsageError("--pack-out 指向的是目录：${file.path}")
        return file
    }

    private fun execute(options: Options, out: PrintStream, err: PrintStream): Int {
        val result = KnowledgeMdImporter.parse(options.kbRoot)
        result.warnings.forEach { out.println("[W] $it") }
        if (!result.isOk) {
            out.println("❌ 解析失败（${result.errors.size} 项错误）——未导入任何数据（整包事务）")
            result.errors.forEach { out.println("  E: $it") }
            err.println("kb-import: ${result.errors.size} validation errors")
            return EXIT_FAILURE
        }

        val pack = result.pack!!
        options.packOut?.let { packOut ->
            packOut.parentFile?.mkdirs()
            packOut.writeText(KnowledgePack.encodePackForCli(pack))
            out.println("[pack] KnowledgePack JSON → ${packOut.absolutePath}")
        }
        if (options.dryRun) {
            out.println("[dry-run] 校验通过，未写库：")
        } else {
            val db = Database.database()
            val tmp = File.createTempFile("kb-import-pack", ".json")
            try {
                tmp.writeText(KnowledgePack.encodePackForCli(pack))
                val stats = kotlinx.coroutines.runBlocking {
                    KnowledgePack.importPack(db, tmp, KnowledgePack.ImportMode.REPLACE_PER_REPO)
                }
                out.println(
                    "✅ 导入完成（REPLACE_PER_REPO）：repos=${stats.repos.size} " +
                        "docs=${stats.docs} tags=${stats.tags} decisionNodes=${stats.decisionNodes} goldenSamples=${stats.goldenSamples}",
                )
            } finally {
                tmp.delete()
            }
        }
        pack.forEach { r ->
            out.println("  repo=${r.repo}: docs=${r.docs.size} tags=${r.tags.size} nodes=${r.decisionNodes.size}")
        }
        return EXIT_OK
    }
}
