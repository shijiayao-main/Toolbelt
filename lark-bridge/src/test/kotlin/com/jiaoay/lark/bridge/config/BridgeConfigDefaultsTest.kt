package com.jiaoay.lark.bridge.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CDS §13.10] 默认文档 12 条断言（旧 model/BridgeConfigTest 6 方法的默认值契约移至此）。
 * 🔴 含 §16.1 裁定①：taskTimeoutSeconds==60L / agentTimeoutMinutes==10L（NF-08 的 600s 是口径混淆，已按 CDS §16.1 更正）。
 */
class BridgeConfigDefaultsTest {

    private val c = DefaultBridgeConfig.default()

    @Test
    fun `taskTimeoutSeconds 默认 60（§16-1 裁定①）`() {
        assertEquals(60L, c.runtime.taskTimeoutSeconds)
    }

    @Test
    fun `agentTimeoutMinutes 默认 10（§16-1 裁定①，NF-08 更正落点）`() {
        assertEquals(10L, c.runtime.agentTimeoutMinutes)
    }

    @Test
    fun `maxFileSizeBytes 默认 1GB（分片批：Range 突破平台 100MB 限后转磁盘保护阈值）`() {
        assertEquals(1_073_741_824L, c.fileMessage.maxFileSizeBytes)
    }

    @Test
    fun `progress 三档截断默认 6-80-2000（2026-09-11 防刷屏裁定 + 2026-09-12 entryMaxChars 暴露为可配）`() {
        assertEquals(2000, c.agent.progress.cardMaxChars)
        assertEquals(6, c.agent.progress.maxEntries)
        assertEquals(80, c.agent.progress.entryMaxChars)
    }

    // 🔴 P2 评审修复（2026-09-12）：产物回传四个默认值是契约（「装完即用」拍板），钉死防漂移。
    @Test
    fun `artifacts 四默认值钉死（enabled=true 装完即用、maxCount=5、25MB、空白名单）`() {
        assertTrue(c.agent.artifacts.enabled, "enabled 默认 true——agent 不写标记行零副作用，装完即用")
        assertEquals(5, c.agent.artifacts.maxCount, "实测依据（多事件分析 skill 按 N+1 产出，4 事件=5 份）后 3→5")
        assertEquals(26_214_400L, c.agent.artifacts.maxSizeBytes, "25MB（26214400B）：飞书 30MB 硬限内留余量")
        assertEquals("", c.agent.artifacts.allowedExtensions)
    }

    @Test
    fun `routes 3 条且 kind 顺序 system-agent-file（P3：AgentRoute 空槽保留）`() {
        // P1（2026-09-26）：/help 内置化进 SystemHandler；P3（2026-09-26）：AgentRoute 匹配键退役
        //（ai 前缀/停止/新对话真源 = EngineReservedWords 常量 + retiredV8 旧词），但空槽保留——
        // CLI 形态（backend 恒非 koog）零自定义 routes 时靠默认槽命中 ai 前缀（App 形态恒短路）。
        assertEquals(3, c.routes.size)
        val kinds = c.routes.map {
            when (it) {
                is SystemRoute -> "system"
                is CommandRoute -> "command"
                is AgentRoute -> "agent"
                is FileRoute -> "file"
            }
        }
        assertEquals(listOf("system", "agent", "file"), kinds)
    }

    @Test
    fun `负向守护-默认 routes 任意字符串字段不含业务专属字样（log_helper 等）`() {
        // 产品约定（2026-09-09）：通用工具不得内置业务专属指令。
        // 遍历默认 routes 的全部字符串字段（sealed 各子类的 String/List<String> 成员），
        // 任何位置出现业务字样即为业务回流，红构建。
        val banned = listOf("log-helper", "log-helper", "/log", "日志分析")
        val strings = buildList {
            c.routes.forEach { r ->
                when (r) {
                    is SystemRoute -> {}
                    is CommandRoute -> { add(r.id); add(r.name); add(r.description); add(r.promptTemplate); addAll(r.aliases) }
                    is AgentRoute -> { add(r.id) }
                    is FileRoute -> { add(r.id) }
                }
            }
        }
        banned.forEach { b ->
            assertTrue(
                strings.none { it.contains(b) },
                "默认 routes 不得含业务字样「$b」，实际字段：$strings",
            )
        }
    }

    @Test
    fun `schemaVersion 默认 == ConfigMigrations-CURRENT_VERSION（同源，终态戳防 migrate 重跑）`() {
        // 🔴 默认值与 CURRENT_VERSION 同源；新建默认配置带当前版本戳，
        //    落盘经 load 时 MIGRATIONS 全 no-op（幂等零重跑）。
        assertEquals(ConfigMigrations.CURRENT_VERSION, c.schemaVersion)
        assertEquals(15, ConfigMigrations.CURRENT_VERSION, "v15：xlog 解密恒启用")
    }

    @Test
    fun `nativeTools 段已退役（L2 恒注册名单单源 + scopeHint 表）`() {
        // L2 授权面收敛批契约：agent.nativeTools 双闸配置段已随 v11→v12 迁移退役——
        // 存量文档 decode 兼容（未知键经 ignoreUnknownKeys + foldUnknownIntoExperimental 保留）。
        // L2 五件恒注册（授权闸收敛为飞书后台 scope 单一闸），名单单源 = l2Tools()。
        assertEquals(
            listOf("fetch_chat_history", "get_chat_info", "create_group_chat", "lookup_user", "read_wiki_document"),
            NativeCapability.l2Tools(),
            "L2 五工具恒注册名单（与 KoogAgentRunner 工具 name 逐字一致）",
        )
        // scopeHint 逐 token 对照（与后台 scope 申请一一对应——工具降级文案与 UI 注脚共同消费）
        assertEquals("im:message.history:readonly", NativeCapability.scopeHintOf("fetch_chat_history"))
        assertEquals("im:chat", NativeCapability.scopeHintOf("get_chat_info"))
        assertEquals("im:chat", NativeCapability.scopeHintOf("create_group_chat"))
        assertEquals("contact:contact.base:readonly", NativeCapability.scopeHintOf("lookup_user"))
        assertEquals("wiki:wiki:readonly", NativeCapability.scopeHintOf("read_wiki_document"))
        assertEquals(null, NativeCapability.scopeHintOf("send_image"), "L1 工具不进 scope 表")
        assertEquals(null, NativeCapability.scopeHintOf("unknown_tool"), "未知工具返回 null")
        // L1 名单钉死（UI 能力卡与 agent 注册面共同消费）
        assertEquals(
            listOf("send_image", "recall_own_message", "reply_in_thread"),
            NativeCapability.l1Tools(),
        )
    }

    @Test
    fun `能力域门控已退役（v13→v14：注册面无配置闸）`() {
        // 五域开关字段已整段删除——工具恒注册，由 TrustPolicy 与逐动作确认卡承担准入。
        // 此处只钉「typing 面不再有开关」这一事实（编译期已背书，运行时留一条断言防复辟）。
        assertEquals(10, c.agent.wsTools.runTaskTimeoutMinutes, "仅数值钳制项保留（默认 10 分钟）")
    }

    @Test
    fun `六行文案契约（label 与 description 非空——IA 批②勾选清单的 UI 直读源）`() {
        // 🔴 IA 重设计批②（2026-09-27）：能力中心勾选清单的中文名/一句人话直读本枚举
        // （label/description/scopeHint 同源 lib 单点，UI 层零文案复制）。
        // 守护面 = 非空（文案具体措辞是设计稿真源，不逐字钉死防正常迭代误伤）。
        NativeToolGrant.entries.forEach { grant ->
            assertTrue(grant.label.isNotBlank(), "${grant.name}.label 不得为空（勾选清单中文名）")
            assertTrue(grant.description.isNotBlank(), "${grant.name}.description 不得为空（一句人话）")
            assertTrue(grant.scopeHint.isNotBlank(), "${grant.name}.scopeHint 不得为空（scope 胶囊）")
        }
        // 与既有测试的 scope 逐字断言互补：六枚举恰好六行（清单条目数守卫）
        assertEquals(6, NativeToolGrant.entries.size)
    }

    @Test
    fun `fileMessage 引用流新默认值`() {
        // 2026-09-11 引用流重设计的域默认值基线
        //（allowedExtensions 已删除——2026-09-26 P0 批确认运行时零消费死配置，不再断言；
        //  ackTemplate 已删除——2026-09-26 P3 批文件登记改默认静默，不再断言）
        assertTrue(c.fileMessage.enabled)
        assertEquals(1_073_741_824L, c.fileMessage.maxFileSizeBytes)
        assertTrue(c.fileMessage.autoExtract)
        assertEquals(72L, c.fileMessage.registryTtlHours)
        assertEquals(2_147_483_648L, c.fileMessage.perUserQuotaBytes, "每用户配额默认 2 GiB")
        assertEquals(8_589_934_592L, c.fileMessage.totalQuotaBytes, "全局配额默认 8 GiB")
        assertEquals("", c.fileMessage.xlogPrivateKey)
        assertEquals("", c.fileMessage.preprocessCommand)
        assertEquals("", c.fileMessage.preprocessExtensions)
    }

    @Test
    fun `confirm timeoutSeconds 默认 60（确认链恒启用，无开关）`() {
        assertEquals(60L, c.agent.confirm.timeoutSeconds)
    }
}
