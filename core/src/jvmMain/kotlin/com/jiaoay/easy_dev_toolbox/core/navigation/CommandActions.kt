package com.jiaoay.easy_dev_toolbox.core.navigation

/**
 * 命令面板动作项契约：feature 模块经
 * [ToolPageRegistration.actions] 把「与页面绑定的可执行动作」上报给宿主命令面板
 * （Ctrl+K）——OpenHands 式「面板不只导航，还能直接干活」。
 *
 * 与 [ToolNavItem]（导航项）的分界：导航项 = 跳转到页面；动作 = 不切页面直接
 * 触发行为（停止运行中的任务 / 新建会话）。两类在命令面板内同列表混排、
 * 各自渲染与执行（sealed 二分，见 CommandPalette）。
 *
 * 纪律（与 navVisible 同理）：动作状态源由 feature 域持有，
 *    消费方在宿主侧——本契约（:core，双方都可见）是唯一合法交点，宿主不 import
 *    feature 实现、feature 不 import 宿主包。
 *
 * 🔴 [perform] 只许**薄转发进 feature 的 object 门面**（先例：AiToolsToggle /
 *    LarkBridgeManager.activityFlow——进程级稳定引用），禁止捕获页面局部态
 *    （Composable 局部 state / VM 实例字段）：注册表是静态构造的，闭包捕获的
 *    局部态会随组合销毁变成死引用。
 *
 * @param id 全局唯一标识（feature 前缀防撞，如 `ai.stop-task`；palette 列表 key 用）
 * @param label 面板展示名（用户动作动词开头，如「停止当前任务」）
 * @param desc 一句话说明（搜索候选之一）
 * @param icon MaterialSymbols 字形名（对齐 [ToolNavItem.icon] 的 String 形态）
 * @param keywords 搜索补充词（英文别名等 label/desc 覆盖不到的检索路径）
 * @param isDangerous 危险动作标注（面板行渲染 error 色 + 执行前 inline 二次确认——
 *        同条目再次 Enter/点击才真执行，Esc 取消；先例：停止运行中任务）
 * @param visibleWhen 可见性谓词：**面板打开瞬间求值一次**（palette 的候选全集在
 *        visible 翻转时重建）——用于「仅运行中可见」类动作。默认恒可见
 * @param perform 执行体（见上方纪律：薄转发，禁捕获局部态）
 */
data class ToolAction(
    val id: String,
    val label: String,
    val desc: String,
    val icon: String,
    val keywords: List<String> = emptyList(),
    val isDangerous: Boolean = false,
    val visibleWhen: () -> Boolean = { true },
    val perform: () -> Unit,
)
