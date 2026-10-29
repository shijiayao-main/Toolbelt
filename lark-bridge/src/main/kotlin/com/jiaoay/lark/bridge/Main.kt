// CLI 入口：clikt 根命令 + 四子命令注册。
// 🔴 零漂移契约：Main-Class = com.jiaoay.lark.bridge.MainKt（shadowJar manifest 已写死，包名/类名不得改）；
//    子命令清单精确为 serve / check / send / handle-event 四个（不新增 register-app，扫码建应用只走 UI）。
// 🔴 复用原包名 com.jiaoay.lark.bridge 使 CLI 入口零漂移是刻意决策（改包名会让既有脚本/文档路径断裂）。
package com.jiaoay.lark.bridge

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.jiaoay.lark.bridge.cli.CheckCommand
import com.jiaoay.lark.bridge.cli.HandleEventCommand
import com.jiaoay.lark.bridge.cli.SendCommand
import com.jiaoay.lark.bridge.cli.ServeCommand

/** `lark-bridge` 根命令：仅承载四子命令注册，自身不执行动作。 */
class LarkBridgeCommand : CliktCommand(name = "lark-bridge") {
    init {
        subcommands(
            ServeCommand(),
            CheckCommand(),
            SendCommand(),
            HandleEventCommand(),
        )
    }

    override fun help(context: Context) =
        "lark-bridge CLI：飞书桥接服务的命令行宿主\n" +
            "配置由 --config <JSON 文档> 承载（默认 ~/.toolbelt/lark/bridge-config.json）。\n" +
            "迁移提示：旧版 --app-id / --app-secret 等 30 个业务字段旗标已移除；改用 --set <点路径>=<值>" +
            "（ephemeral，永不落盘，只影响本次进程）覆写单个字段，或直接编辑 --config 指向的 JSON 文档；" +
            "全字段点路径表见 check 子命令输出。"

    override fun run() = Unit
}

/** CLI 进程入口（= manifest Main-Class `com.jiaoay.lark.bridge.MainKt`）。 */
fun main(args: Array<String>) = LarkBridgeCommand().main(args)
