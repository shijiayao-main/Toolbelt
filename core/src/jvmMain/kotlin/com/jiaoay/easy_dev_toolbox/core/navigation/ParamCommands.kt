package com.jiaoay.easy_dev_toolbox.core.navigation

import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 带参命令契约（调查工作台 S3 命令面板升格——融合案 D「参数直填 + HUD」）。
 *
 * ## 定位
 * 命令面板第三类候选（前两类 = 导航项 [ToolNavItem] / 无参动作 [ToolAction]）：
 * 「带参数的确定性命令」——palette 选中后进参数直填态（[ParamSpec] 逐字段表单化），
 * 执行结果进 HUD 输出区。五件套页面（XLog 解密 / Tag 过滤 / 分割 / 时间戳 / 解压）
 * 退役后经本契约以命令形态存活（原子动作型——14 页退役映射表）。
 *
 * ## 与 [ToolAction] 的分界（既有动作面零回归）
 * - ToolAction：无参、非挂起、`perform: () -> Unit`——「停止运行中任务」类即时动作；
 * - ParamCommandSpec：带参、挂起（解密/过滤/解压全是 IO）、`[performArgs]` 返回
 *   结构化结果（HUD summary + 产物 chips）。两契约并存互不侵入。
 *
 * ## 纪律（对齐 ToolAction 的纪律）
 * 🔴 [performArgs] 只许**薄转发**进 object 门面 / 纯函数直调 / agent 工具投影执行器
 *    （进程级稳定引用），禁止捕获 Composable 局部态——注册表是静态构造的，闭包捕获
 *    的局部态会随组合销毁变成死引用。
 *
 * ## 深入模式（枚举收敛终态）
 * [openPage] 非 null = 该命令对应的原页面仍可达（content 注册保留）：palette 行尾
 * 「打开页面」钮 / Ctrl+Enter → 复用现有 onNavigate 跳页（WorkspaceTabs 临时 tab 承载）。
 * 枚举收敛已定案：五件套枚举值**保留**（深入模式仍以页 content
 *    形态渲染，ToolPage 枚举是 content 注册的可达性锚——见 ToolPage.kt 终态注释），
 *    本字段维持 ToolPage 形态长期有效。
 *
 * @param id 全局唯一标识（feature 域前缀防撞，如 `logkit.xlog-decrypt`；agent 工具
 *        投影直接用目录名 `log.filter_by_time`）——palette 列表 key 前缀 `param:` 隔离
 * @param label 面板展示名（动作动词开头）
 * @param desc 一句话说明（搜索候选之一）
 * @param icon MaterialSymbols 字形名（String 形态，对齐 ToolNavItem.icon 契约）
 * @param keywords 搜索补充词（英文别名等 label/desc 覆盖不到的检索路径）
 * @param params 参数 schema（逐字段表单化依据；空表 = 无参命令，跳过表单直接执行——
 *        与 ToolAction 语义重叠面仍保留本类型；S3 后注册面全量命令均带参，空表是
 *        契约允许的直通分支而非常态形态）
 * @param performArgs 执行体（挂起：IO 命令；返回 [ParamCommandResult]——HUD 文案 + 产物路径）
 * @param isDangerous 危险命令视觉标注（error 色，对齐 ToolAction.isDangerous）
 * @param visibleWhen 可见性谓词：面板打开瞬间求值一次（对齐 ToolAction 契约）。
 *        投影面用例：知识库空库时隐藏 knowledge.search（读 palette 侧预取快照）
 * @param openPage 深入模式跳页目标（null = 纯命令无页面形态）
 */
data class ParamCommandSpec(
    val id: String,
    val label: String,
    val desc: String,
    val icon: String,
    val keywords: List<String> = emptyList(),
    val params: List<ParamSpec> = emptyList(),
    val performArgs: suspend (args: Map<String, String>) -> ParamCommandResult,
    val isDangerous: Boolean = false,
    val visibleWhen: () -> Boolean = { true },
    val openPage: ToolPage? = null,
)

/**
 * 带参命令执行结果（HUD 输出区数据源）。
 *
 * @param summary 结果文案（成功首行 / 失败原因——工具 render 文案或薄转发自述）
 * @param artifacts 产物路径列表（HUD chips；点击按产物形态分流：文本日志形态产物
 *        （txt/log/md/diff 扩展名清单，大小写不敏感）进应用内查看器，其余产物经
 *        DesktopUtils.openFile 由系统默认程序打开、失败降级打开所在目录）
 * @param success 执行是否成功（HUD 配色依据：失败 error 色）
 */
data class ParamCommandResult(
    val summary: String,
    val artifacts: List<String> = emptyList(),
    val success: Boolean = true,
)

/**
 * 命令参数 schema（PaletteParamForm 逐字段表单化依据）。
 *
 * @param id 参数标识（必须与底层实现的 Args 字段名逐字一致——ToolCatalogDisciplineTest
 *        硬编码断言防漂移）
 * @param label 表单标签
 * @param type 表单形态（FILE=路径文本+存在性校验 / TEXT=文本 / NUMBER=数字 / CHOICE=chips 单选）
 * @param default 预填默认值（字符串统一形态——NUMBER 也存字符串，执行器按 type 转 JSON 数值）
 * @param required 是否必填（未填齐禁执行）
 * @param options CHOICE 可选项（仅 type=CHOICE 时非空）
 * @param placeholder 输入提示（如时间格式 yyyy-MM-dd_HH:mm:ss.SSS）
 */
data class ParamSpec(
    val id: String,
    val label: String,
    val type: ParamType,
    val default: String? = null,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
)

/** 参数表单形态四型（ParamSpec.type） */
enum class ParamType { FILE, TEXT, NUMBER, CHOICE }
