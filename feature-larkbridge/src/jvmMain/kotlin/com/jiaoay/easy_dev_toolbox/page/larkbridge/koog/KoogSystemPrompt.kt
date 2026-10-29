package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.lark.bridge.agent.AgentRequest

internal fun buildKoogSystemPrompt(
    request: AgentRequest,
    knowledgeAvailable: Boolean,
    stylePrompt: String?,
    safetyPrompt: String?,
    styleOverridePrompt: String? = null,
    sandboxAvailable: Boolean = false,
    artifactsEnabled: Boolean = true,
    registeredToolNames: Set<String> = emptySet(),
): String = buildString {
    val listed = { name: String -> registeredToolNames.isEmpty() || name in registeredToolNames }
    appendLine(KoogAgentRunner.SYSTEM_PROMPT_HEAD)
    appendLine()
    when {
        // per-user 专属档：替换全局段位——段头明示「仅在与你的对话中生效」
        // （身份边界写进段头：专属档不得影响其他用户的观感）。
        !styleOverridePrompt.isNullOrBlank() -> {
            appendLine("以下是为你单独配置的性格模式设定（仅在与你的对话中生效；决定你回复的整体语气与风格，用户可见的回复都应符合它）：")
            appendLine(styleOverridePrompt)
            appendLine()
        }
        !stylePrompt.isNullOrBlank() -> {
            appendLine("以下是全局性格模式设定（决定你回复的整体语气与风格；用户可见的回复都应符合它）：")
            appendLine(stylePrompt)
            appendLine()
        }
    }
    if (request.persona.isNotBlank()) {
        appendLine("以下是这个机器人的人设设定（决定你回复的语气与风格；用户可见的回复都应符合它；若与全局性格模式冲突，以本段为准）：")
        appendLine(request.persona)
        appendLine()
    }
    // 有引用文件（DispatchPayload 载体）才注入文件工具段——
    // 无文件上下文的纯聊天消息不给这些工具，提示词与注册表同步省。
    val hasFileContext = (request.opaque as? com.jiaoay.lark.bridge.pipeline.DispatchPayload)
        ?.allowedFiles?.isNotEmpty() == true
    appendLine("可用工具：")
    // 单源派生：每行挂 listed()——注册集非空时只列实际注册的工具。
    if (listed("reply_direct")) {
        appendLine("- reply_direct(text)：直接答复用户——轻量问题（闲聊/概念解释/快速判断）首选，不要转发重后端。")
    }
    // 总开关关闭时**不列** send_file（能力面与 execute 对齐——它此时恒拒）。
    //    列出再靠描述劝退会在提示词里留下自相矛盾（「可用工具」清单里挂一个恒失败的工具）。
    if (artifactsEnabled && listed("send_file")) {
        appendLine("- send_file(file_path, display_name)：把本地文件作为飞书文件消息发给用户（过程产物交付）。")
    }
    // L1 三件（恒注册）——send_image 与 send_file 同围栏，随开关联动。
    if (artifactsEnabled && listed("send_image")) {
        appendLine("- send_image(file_path, display_name)：把本地图片（图表/截图等产物）作为飞书图片消息发给用户（围栏同 send_file）。")
    }
    if (listed("send_card")) {
        appendLine("- send_card(title, markdown)：发送一张富文本卡片（结构化结果展示）。")
    }
    // P4 会话作用域三件
    if (listed("react_emoji")) {
        appendLine("- react_emoji(emoji_key)：给用户当前消息加表情回应（轻量致意/确认，不替代文字回复）。")
    }
    if (listed("fetch_quoted_context")) {
        appendLine("- fetch_quoted_context()：取回用户引用的机器人卡片回复全文（本地账本，仅本会话）。")
    }
    if (listed("update_own_card")) {
        appendLine("- update_own_card(message_id, title, markdown)：原位更新你自己在当前会话发过的卡片（修正/精修刚发的卡）。")
    }
    // L1 三件的另两件（recall_own_message / reply_in_thread）
    if (listed("recall_own_message")) {
        appendLine("- recall_own_message(message_id)：撤回机器人自己刚发的消息——messageId 取自本会话发送结果，不是用户消息；只能撤自己发的。")
    }
    if (listed("reply_in_thread")) {
        appendLine("- reply_in_thread(text)：群聊话题内回复（保持讨论线索）；单聊自动按普通回复发送。")
    }
    // 长期记忆四件（memory.*，绑定本会话跨 run 存续）——刻意列在会话工具段而非
    //    文件处理工具段：记忆目录不进本消息文件围栏（memory 自带 memoryDir 围栏），
    //    文件围栏计数句（「以上二十件读类工具」）不含 memory。
    if (listed("view_memory")) {
        appendLine("- view_memory(file_name)：读长期记忆（跨会话存续）——会话开始时先无参读索引，回忆本聊天已有的背景知识（设备型号/环境/排障习惯/私钥位置等）；本消息内的临时上下文不要写进长期记忆。")
    }
    if (listed("append_memory")) {
        appendLine("- append_memory(file_name, title, hook, content)：写长期记忆（跨会话存续）——用户交代跨会话有价值的背景知识/偏好/纠正时写入（一事一文），下次会话仍可读到。")
    }
    if (listed("replace_memory")) {
        appendLine("- replace_memory(file_name, old_text, new_text)：修正长期记忆——新信息与旧条目矛盾时改写对应段落（勿用追加堆叠矛盾内容）。")
    }
    if (listed("delete_memory")) {
        appendLine("- delete_memory(file_name)：删除已证伪或过时的记忆文件（清文件+索引行）。")
    }
    // 会话内提醒（条件注册——宿主注入 scheduler 才在册，listed() 单源派生自动门控）
    if (listed("remind_user")) {
        appendLine("- remind_user(delay_minutes, text)：N 分钟后回到本会话提醒用户（text 原文透传，一次性）——用户在等长任务结果/要求稍后复查时用；不支持周期定时任务，也不用于即时问题；设定时如实告知用户：桌面应用退出或桥接停止提醒会丢失。")
    }
    // 提醒撤销（与 remind_user 同条件注册——同一 scheduler 注入门控）
    if (listed("cancel_reminder")) {
        appendLine("- cancel_reminder(reminder_id)：取消一条还没响起的会话内提醒（reminder_id 取自 remind_user 回执里的编号）——计划有变、任务提前完成或用户说「不用提醒了」时用；只能取消本会话设的提醒；已在送达途中的会如实报「无法撤销、消息可能仍送达」，已到期或已取消的编号会如实报不存在。")
    }
    // L2 五件（跨会话能力，接缝在位即注册——授权闸在飞书后台 scope，非本地配置）——
    //    全部挂 listed()（单源派生自动门控）；每行注明「跨会话能力」属性。
    if (listed("fetch_chat_history")) {
        appendLine("- fetch_chat_history(limit, page_token)：拉取当前会话的历史消息（跨会话能力）——查早前讨论/找上下文用，page_token 续页。")
    }
    if (listed("get_chat_info")) {
        appendLine("- get_chat_info()：查当前会话信息与成员名单（跨会话能力）——群名/描述/成员 open_id（前 50 名）。")
    }
    if (listed("create_group_chat")) {
        appendLine("- create_group_chat(name, member_open_ids)：创建新群（跨会话能力；🔴 写操作，每次都须经用户确认卡批准才执行；机器人不能主动向新群发消息，成功后引导用户在新群 @ 机器人）。")
    }
    if (listed("lookup_user")) {
        appendLine("- lookup_user(open_id)：把成员/历史里的 open_id 解析成用户名（跨会话能力；只回名字，不返回其他个人信息）。")
    }
    if (listed("read_wiki_document")) {
        appendLine("- read_wiki_document(url)：读飞书知识库/文档正文（跨会话能力）——支持 /wiki/ 与 /docx/ 链接或 wiki 节点 token，超长自动截断。")
    }
    // web.fetch：白名单域名网页拉取（联网查询面——查错误码/官方文档/AOSP 源码）。
    //    刻意列在文件围栏段之外（不进「文件处理工具」节，围栏计数句不含它）：
    //    它操作的是白名单 URL 不是文件路径，进围栏段会让「只能操作用户引用发送的文件」
    //    的围栏句对它失真。桥接链恒注册（纯聊天与有引用文件消息同在册——防线全在
    //    WebTools 工具本体，与本消息文件围栏无关）。
    if (listed("web.fetch")) {
        appendLine("- web.fetch(url)：拉取白名单域名（AOSP 源码/Android 官方文档/GitHub/askubuntu 问答/Launchpad bug 追踪/GNOME 仓库）网页转纯文本（≤8K 字符，重定向到白名单外域名会被拒）——查错误码含义、官方文档、AOSP 源码及本机环境已知问题且有确切 URL 时用它；拿不准地址或闲聊不要乱试，拉取失败会如实报错，禁止编造页面内容。")
    }
    // sys.* 两件（P1c 条件注册——config 门控 + 会话级首次总授权卡面；listed() 单源派生自动门控）。
    //    刻意不进文件处理工具段/围栏计数句：读取面是编译期目录/路径族，不读本消息文件围栏。
    if (listed("sys.probe")) {
        appendLine("- sys.probe(key, …)：只读探查本机桌面环境（输入法/GNOME 扩展/用户级服务/用户日志/桌面会话基础——只能从编译期目录选条目，不能自造命令；输出已脱敏、≤8K 字符；非零退出码是诊断答案不是失败）。")
    }
    if (listed("sys.read_config")) {
        appendLine("- sys.read_config(family, relative_path)：只读编译期路径族内的配置文件（fcitx5 配置族 / xprofile / GNOME 扩展 manifest 摘要——只能选族，不能自造路径；超 256KB 整体拒读）。")
    }
    if (listed("sys.repair")) {
        val verifyStep = if (listed("sys.verify")) {
            "执行后用 sys.verify 复跑验证（回执里的 pre-state 即修复前快照，作为 before 回传）"
        } else {
            "执行后用 sys.probe 复查验证"
        }
        appendLine("- sys.repair(key, …)：执行白名单修复动作（设置输入法框架为 fcitx5 / 后台拉起 fcitx5 守护 / 启用 GNOME 扩展 / 设置 GNOME 扩展相关配置键——只能从编译期目录选动作，不能自造命令）。信任用户直接执行；非信任用户先经其确认卡批准，卡面含完整命令与回滚配方；执行前自动捕获原值供回滚，$verifyStep。")
    }
    if (listed("sys.write_config")) {
        appendLine("- sys.write_config(family, relative_path, content)：覆写白名单写族内的纯数据配置文件（当前仅 fcitx5 配置族 ~/.config/fcitx5 内——只能选族与族内相对路径，不能自造路径）。信任用户直接写入；非信任用户先经其确认卡批准完整前后 diff，写前自动落 .bak 快照、卡面带回滚配方；diff 超展示预算会被整体拒绝，不要分片绕过；登录启动脚本类文件（~/.xprofile 等）与凭据路径永不入写族。写后用 sys.read_config 复查。")
    }
    if (listed("sys.service")) {
        appendLine("- sys.service(service, action)：执行用户级服务动作（systemctl 恒带 --user；动作限 restart/start/stop，状态查询走 sys.probe 的 systemd_user_status；服务白名单七项——输入法/音频/portal 族，桌面会话级单元如 gnome-shell 永不入目录）。信任用户直接执行；非信任用户先经其确认卡批准（卡面含完整命令、DESTRUCTIVE 徽标与回滚配方），执行后用 sys.probe 复查服务状态。")
    }
    if (listed("sys.verify")) {
        appendLine("- sys.verify(key, …)：修复后验证回环——按修复动作（im_config_set_fcitx5 / fcitx5_restart_daemon / gnome_extension_enable / gsettings_set）自动复跑对应只读探查，对比修复前后状态判定是否生效（生效/未生效/无法判定三态，槽传修复时的同值，before 传 sys.repair 回执里的 pre-state）。未生效会自动复跑一次，仍失败则降级外委：把手动检查命令与回滚配方交用户本人执行，不要自动重试写动作。")
    }
    if (listed("sys.handoff")) {
        appendLine("- sys.handoff(purpose, steps)：把超出你执行边界（root/会话级/白名单外）的步骤整理成结构化命令清单交用户本人在终端执行——每步含命令/作用/风险/回退，本工具不执行任何命令、回执明示全部未经执行；用户跑完把输出贴回来再继续分析。不代执行、不伪装已执行。")
    }
    // dev.* 两件（设备域条件注册——config 门控 + 会话级首次总授权卡面；listed() 单源派生自动门控）。
    //    刻意不进文件处理工具段/围栏计数句：读取面是设备检测单源与固定 argv 模板，
    //    不读本消息文件围栏的初始允许集（dev.snaplog 产物登记后分析链才消费）。
    if (listed("dev.devices")) {
        appendLine("- dev.devices()：只读列出当前连接到本机的 Android 设备（串号 + 型号，每次现检测）——串号原样作为 dev.snaplog 的 serial 参数；无设备是正常答案，引导用户连接并开启 USB 调试。")
    }
    if (listed("dev.snaplog")) {
        appendLine("- dev.snaplog(serial, lines)：抓取设备日志（logcat）快照到本机（固定模板 dump，只能选设备与行数 10..5000 缺省 1000；serial 只能取 dev.devices 刚报出的串号）——快照已登记进文件围栏，用 log.stats / log.cluster / log.view / log.filter_by_tag 直接分析；快照文件不发飞书，回复分析结论。")
    }
    // self.status 单件（自观测域条件注册——config 开关 + 状态源缝；listed() 单源派生自动门控）。
    //    刻意不进文件处理工具段/围栏计数句：零文件面，读的是桥接进程内聚合快照。
    if (listed("self.status")) {
        appendLine("- self.status()：只读回报本桥接机器人自身的运行健康（连接态与重连计数 / 流水线计数 / 配置校验摘要 / 今日 sys-audit 聚合计数）——用户问「机器人还正常吗/桥接什么状态」或你需确认出站通道是否在服务时，先查它再回答；审计部分只回报聚合计数（如「今日 probe 3 次拒绝 1 次」），工具面不存在任何审计原文或命令内容，也不要向用户转述超出聚合数的细节。")
    }
    if (hasFileContext) {
        appendLine()
        appendLine("文件处理工具（🔴 分工原则：以下任务**必须优先用这些本地工具**完成——快、零进程开销；本地工具覆盖不了的深度分析先检查 git.read / ws.run_task 能否补足）：")
        if (listed("probe_file")) appendLine("- probe_file(file_path)：探测文件是否为 xlog/alog 加密日志（判型+是否需私钥）——处理不确定的文件先探测再决定。")
        if (listed("decrypt_log")) appendLine("- decrypt_log(file_path)：解密设备加密日志（.xlog/.alog）→ 同目录可读 .log。")
        if (listed("filter_by_tag")) appendLine("- filter_by_tag(file_path, tag_regex, tag_name)：按 Tag 正则过滤日志块（保留完整块）→ 输出 .txt。")
        if (listed("filter_by_time")) appendLine("- filter_by_time(file_path, target_time, range_minutes)：按时间点±范围过滤 → 输出 .txt。")
        if (listed("filter_by_range")) appendLine("- filter_by_range(file_path, start_time, end_time)：按起止时刻截取任意区间（含两端；对称窗口整数分钟表达不了的奇数分钟区间用它） → 输出 .txt。")
        if (listed("split_by_size")) appendLine("- split_by_size(file_path, size_mb)：大文件按 MB 分割成多卷。")
        if (listed("extract_archive")) appendLine("- extract_archive(archive_path)：解压压缩包（zip/tar/gz 族）。")
        if (listed("preview_text")) appendLine("- preview_text(file_path)：文本头 4KB 预览（判断内容/编码）。")
        if (listed("list_dir")) appendLine("- list_dir(dir_path)：列目录清单。")
        // 扩展件：:core 大文件分析/取证面——与注册表 extended=true 改齐
        //（一致性纪律：工具清单/注册表/systemPrompt 三处必须同步改，否则留下
        // 「列了没注册」或「注册了没列」的能力面错位）。不列 write_todos（飞书无 todo 投影面）。
        if (listed("read_file")) appendLine("- read_file(file_path, offset, limit)：按行分页读取文本文件（带行号，默认每页 200 行）——大文件分析用它翻页，不要 preview_text 反复试。")
        if (listed("grep")) appendLine("- grep(pattern, path)：字面量搜索文件或目录（递归，返回命中行+行号，上限 1000 条）——找 ERROR/ANR 等关键词首选。")
        if (listed("glob")) appendLine("- glob(dir_path, pattern)：通配模式列文件（如跨目录找全部 xlog 文件）。")
        if (listed("convert_timestamp")) appendLine("- convert_timestamp(value)：时间戳↔可读时间双向转换。")
        if (listed("hash_file")) appendLine("- hash_file(file_path)：文件 SHA-256/MD5 校验（验证产物完整性）。")
        if (listed("hex_dump")) appendLine("- hex_dump(file_path, offset, length)：十六进制转储（检查文件头/二进制结构，length≤4096）。")
        if (listed("strings")) appendLine("- strings(file_path, min_length)：提取二进制文件中的可打印字符串（取证）。")
        // 侦察四件（registry 调用名即点分目录名，与上面扩展件的裸名不同）：动线「统计→下钻→引用」
        // ——语义与 LogStatsDiffTools 描述符单源，勿在此另造口径。
        if (listed("log.stats")) appendLine("- log.stats(file_path)：整份纯文本日志的分布概览（日志块/级别计数/Tag 频次 Top 30/时间跨度）——几万行日志先看分布再定过滤策略，零产物。")
        if (listed("log.diff")) appendLine("- log.diff(old_file_path, new_file_path, normalize_timestamps)：两份日志行级对比（有界摘要：hunk 数 + 首 hunk）——两份日志先对比找行为差异再下钻差异窗口，零产物。")
        if (listed("log.view")) appendLine("- log.view(file_path, start_line, window_lines)：按 1 基行号读原文窗口——log.stats/log.diff 定位到区域后用它引用原文，零产物。")
        if (listed("log.cluster")) appendLine("- log.cluster(file_path, levels, limit)：错误聚类——E/F 级块默认归并成 top-N 模板摘要（模板串/计数/首末时间/块首行号样例），同型异常刷屏先看 top-N 模板定优先级，再按行号样例用 log.view 下钻原文，零产物。")
        appendLine("文件围栏：以上二十件读类工具只能操作**用户引用发送的文件、其下载目录及你的工具产物**——路径不在此围栏内会收到错误提示。")
        // 🔴 send_file 的放宽**必须单列**——不能并进上一句（勿把上一句改成
        //    「以上工具与 send_file」）：LLM 会误读读类工具也放宽、反复用 preview_text
        //    试错工作区内文件，耗尽 maxIterations（同型事故根因）。上一句因此明确写
        //    「以上二十件读类工具」不含 send_file——与紧随其后的放宽例外句互斥；
        //    增删本节任何一条工具行，必须同步改计数句与列出行数（守护测试钉死两者一致）。
        if (artifactsEnabled) {
            if (sandboxAvailable) {
                appendLine("send_file 的边界（比读类工具宽）：用户引用的文件、你的工具产物，或**位于工作区内的文件**（含你的工作区工具产出的报告）。")
            } else {
                appendLine("send_file 的边界：与读类工具相同（本机未配置工作区，工作区放宽不生效）——只能发送用户引用的文件与你的工具产物。")
            }
            appendLine("send_file 不放行凭据/密钥类文件（私钥、证书、.env、凭据文件、桥接自身的配置数据）——命中时会被拒，请改用其他产物。")
        }
    }
    if (knowledgeAvailable && listed("search_knowledge")) {
        appendLine("- search_knowledge(query)：检索本工具的官方使用手册。回答「这个工具怎么用 / 有什么功能 / 某按钮在哪」等用法问题前**必须先查它**，基于检索到的手册内容回答，不要凭空编造。")
    }
    // 工作区域三件（ws/git 域条件注册——config 门控 + 会话级首次总授权卡面；listed() 单源派生自动门控）。
    //    刻意不进文件处理工具段/围栏计数句：读取/写入面是工作区根（装配事实），
    //    不读本消息文件围栏（ws 专用 context 的 escapeGate 放行）。
    if (listed("ws.write_file")) {
        appendLine("- ws.write_file(relative_path, content)：在工作区根内写文件（相对路径，拒绝代建目录）。信任用户直接写入；非信任用户先经其确认卡批准完整前后 diff，写前自动落 .bak 快照；diff 超展示预算会被整体拒绝——大改动分多次小步写入。写成功后文件已登记进围栏，可直接 send_file 交付。")
    }
    if (listed("ws.run_task")) {
        appendLine("- ws.run_task(command, args, timeout_minutes)：在工作区根执行白名单构建/测试任务（只能选配方：gradle / maven / npm / pnpm，不能自造命令）。每次执行先经用户确认卡批准（卡面含完整命令与超时），长任务输出会流式进进度卡；任意命令与解释器（python/node）不在目录内。")
    }
    if (listed("git.read")) {
        appendLine("- git.read(action, path)：对工作区根内的 git 仓库做只读查询（log 近期提交 / diff 工作区差异 / blame 文件注解 / status 工作区状态；只能选动作枚举）。写操作（commit/push/checkout 等）永久不在能力面内。")
    }
    if (listed("user.ask_user")) {
        appendLine("- user.ask_user(question, fields)：向用户提问补齐缺失信息（一次把所有问题问全——多字段批量提问）。信息不足且会阻塞任务时用它，不要瞎猜；用户引用回答或点击按钮后你会收到答案，跳过/超时会收到软反馈并基于现状继续。")
    }
    // 本机环境顾问段：bot 无本机命令执行能力——环境类求助只做「查已知问题 +
    //    给诊断思路 + 给用户自跑的命令清单」纯咨询形态，命令清单是纯文本建议。
    //    挂 listed("web.fetch") 门控：段内引用该工具，注册集不含它时整段缺席
    //    （与清单行同源，不列没注册的工具）。
    if (listed("web.fetch")) {
        appendLine()
        appendLine("本机环境求助处理纪律（用户问输入法/桌面环境/系统服务等本机环境类问题时）：")
        appendLine("- 先用 web.fetch 查白名单知识域的已知问题：askubuntu.com（Ubuntu 问答）、launchpad.net（Ubuntu bug 追踪）、gitlab.gnome.org（GNOME 扩展与组件仓库）——系统升级后失效类问题多为已知问题族，先查再答，查不到时如实说明并基于通用知识给思路；")
        appendLine("- 给出诊断思路与**可复制的命令清单**：命令由用户本人在自己的终端执行，逐条说明它做什么、输出怎么读、有什么风险、出问题如何回退；")
        appendLine("- 话术对非开发者友好：用白话解释因果，不堆术语，不假设用户看得懂 man 手册；")
        appendLine("- 明确告知能力边界：你当前只能「看得到、查得到」，不能代为执行任何本机命令——所有命令由用户本人执行并自行决定。")
    }
    // sys.* 诊断纪律段（装配后的使用纪律；listed() 同源门控——任一 sys.* 在册才注入）。
    //    不进本机环境顾问段（该段是「零执行能力」纯咨询形态，sys.* 装配后纪律面独立成段）。
    //    写动作（repair/write/service）在册时「零执行能力」句退位为对应纪律句（同段互斥
    //    分支——清单与注册面三处同步）。verify/handoff 在册时补验证回环与外委边界纪律
    //    （诊断 → 修复 → 验证 → 外委 的完整闭环；各行挂各自 listed 门控，不列没注册的工具）。
    if (listed("sys.probe") || listed("sys.read_config") || listed("sys.repair") ||
        listed("sys.write_config") || listed("sys.service") ||
        listed("sys.verify") || listed("sys.handoff")
    ) {
        appendLine()
        appendLine("本机诊断纪律（sys.* 工具使用时）：")
        appendLine("- 先探查后结论：用 sys.probe / sys.read_config 的只读面收集证据再下诊断结论；探查不到或目标不存在时如实说明，禁止编造；")
        appendLine("- 脱敏输出如实告知：工具输出里的凭据形态（token/key/password 的值）已被统一遮蔽为「***」，向用户转述时如实说明已脱敏，不要试图还原或向用户索要原值；")
        if (listed("sys.repair")) {
            val verifyStep = if (listed("sys.verify")) {
                "执行 → sys.verify 验证 → 验证不通过再外委"
            } else {
                "执行 → 验证"
            }
            appendLine("- 修复动作纪律：诊断 → 给方案 → 用户确认 → $verifyStep——修复只能走 sys.repair 的白名单动作（信任用户直接执行；非信任用户先经其确认卡批准），" +
                (if (listed("sys.verify")) "执行后立即用 sys.verify 复跑对应探查判定是否生效（before 传回执里的 pre-state）；" else "执行后立即用 sys.probe 对应条目复跑验证；") +
                "验证不通过时如实报告，不要连续重试写动作，把回滚配方交用户本人在终端执行；")
            appendLine("- 修复边界保持：sys.repair 只承载白名单内的输入法与 GNOME 扩展配置动作——删除类/磁盘/网络/持久化面（自启动、登录启动脚本、systemd 单元）/提权（sudo）动作永不执行，需要时" +
                (if (listed("sys.handoff")) "用 sys.handoff 整理成结构化清单" else "以可复制命令清单") +
                "交用户本人，并逐条说明作用、风险与回退方法。")
        }
        if (listed("sys.verify")) {
            appendLine("- 验证回环纪律：修复动作执行后先用 sys.verify 复跑对应探查判定是否生效（before 传 sys.repair 回执里的 pre-state）——判定生效才向用户报告成功；未生效复跑一次仍失败即降级外委，不要自动重试写动作；无法判定时如实说明，改用 sys.probe 人工复查或把检查命令交用户；" +
                (if (listed("remind_user")) "需要等待观察或用户不在场时，可建议用户用 remind_user 设个提醒稍后回来复查。" else ""))
        }
        if (listed("sys.handoff")) {
            appendLine("- 外委边界纪律：超出 sys.* 白名单面的动作（root/会话级/目录外步骤）永不硬凑执行——用 sys.handoff 整理成结构化清单（命令/作用/风险/回退逐条列出）交用户本人在终端执行，话术明示「超出我的能力边界，需要您本人在终端执行」与「未经执行」；用户贴回执行输出后再继续分析，不代执行、不伪装已执行。")
        }
        if (listed("sys.write_config")) {
            appendLine("- 配置写纪律：诊断 → 给方案 → 用户确认 diff → 执行 → 验证——写入只能走 sys.write_config 的唯一写族（fcitx5 配置族），信任用户直接写入；非信任用户先经其确认卡批准完整前后 diff，写前自动落 .bak 快照，写入后立即用 sys.read_config 复查；diff 超展示预算会被整体拒绝，不要试图分片绕过；登录启动脚本类文件（~/.xprofile 等每次登录被执行的持久化面）与凭据路径永不写入，相关需求交用户本人执行。")
        }
        if (listed("sys.service")) {
            appendLine("- 服务动作纪律：服务重启/启停只能走 sys.service 的白名单动作（systemctl 恒带 --user；动作限 restart/start/stop，状态查询走 sys.probe），信任用户直接执行；非信任用户先经其确认卡批准（卡面含 DESTRUCTIVE 徽标与回滚配方），执行后立即用 sys.probe 复查服务状态；桌面会话级单元（如 gnome-shell，重启等同重启整个桌面会话）永不操作，需要时" +
                (if (listed("sys.handoff")) "用 sys.handoff 整理成清单" else "以可复制命令") +
                "交用户本人。")
        }
        if (!listed("sys.repair") && !listed("sys.write_config") && !listed("sys.service")) {
            appendLine("- 零执行能力边界保持：你只能只读探查（白名单目录 + 编译期路径族），不能修改任何系统配置/服务状态/输入法设置——修复建议" +
                (if (listed("sys.handoff")) "用 sys.handoff 整理成结构化清单" else "以可复制的命令清单") +
                "交用户本人执行，并逐条说明作用、风险与回退方法。")
        }
    }
    // dev.* 设备观测纪律段（装配后的使用纪律；listed() 同源门控——任一 dev.* 在册才注入）。
    //    与 sys.* 纪律段分开（两域读取对象不同：主机环境 vs 连接设备）。
    if (listed("dev.devices") || listed("dev.snaplog")) {
        appendLine()
        appendLine("设备观测纪律（dev.* 工具使用时）：")
        appendLine("- 只读观测边界：dev.* 只能列设备与抓日志快照——设备上永不执行安装/卸载/推送文件/删除/截屏/按键注入等任何操作，此类请求如实告知超出能力边界；")
        appendLine("- serial 白名单：dev.snaplog 的 serial 只能取 dev.devices 刚报出的串号（不在检测列表内会被拒收），先列设备再抓快照；行数先用缺省，需要更早窗口再加大；")
        appendLine("- 快照分析动线：抓到快照先用 log.stats 看分布、log.cluster 聚类错误，再用 log.view 引用原文行号——回复分析结论与证据行，不要整段转述快照原文；")
        appendLine("- 隐私如实告知：设备日志可能含个人数据残迹——快照只落本机、永不发往飞书，向用户转述时只引用与问题相关的行，不复述无关个人信息。")
    }
    appendLine()
    // 交付约定整段随总开关改写——关闭时**不得**出现任何「用 send_file」或
    //    `[产物]` 标记的诱导（标记链在此开关下同样被跳过，写了就是死路 + 试错）。
    if (artifactsEnabled) {
        appendLine("交付约定：如需向用户交付产物文件，优先用 send_file 工具发送（你的工作区工具产出已登记进围栏，直接发送即可）。仅在无法调用 send_file（如工具持续失败）时，才在**最终回复末尾**每行一条 ` [产物] <文件绝对路径>` 标记由桥接统一上传兜底——同一文件不要双通道重复交付。")
    } else {
        appendLine("交付约定：本机已关闭「产物回传」，**任何文件都无法发送**——不要调用 send_file，也不要在回复末尾写 `[产物]` 标记（都不会生效）。请把结果以文字（reply_direct）或卡片（send_card）交付；若用户需要文件，如实告知需先在飞书桥接设置中开启「产物回传」。")
    }
    if (request.systemPrompt.isNotBlank()) {
        appendLine()
        appendLine("以下为用户的附加系统指令（优先级高于以上默认约束的细节，但不得违反安全底线）：")
        appendLine(request.systemPrompt)
    }
    if (!safetyPrompt.isNullOrBlank()) {
        appendLine()
        appendLine(safetyPrompt)
    }
}

/**
 * 提示词风格快照（[KoogAgentRunner.refreshEndpoint] 每次运行前重建）：
 * 全局风格档 + per-user openId→档位映射 + 安全档正文，经 [buildKoogSystemPrompt] 注入。
 */
internal data class KoogPromptConfig(
    val styleId: String = "default",
    val styleCustom: String = "",
    val userStyles: Map<String, String> = emptyMap(),
    val safetyPrompt: String? = null,
)
