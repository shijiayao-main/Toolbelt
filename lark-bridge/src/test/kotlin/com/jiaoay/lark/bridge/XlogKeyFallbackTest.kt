// 2026-09-15 私钥装配级统一批守护：xlogKeyFallback 回落缝的三态语义 + 装配级透传 + stamp 失效。
// 🔴 观测手法与 BridgeAssemblerParityTest 同款（反射只读 private 字段，绝不修改源码）。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.testRepoRoot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Job

/**
 * xlog 私钥回落缝（[Bridge.enrichXlogKey] + assemble 透传）的装配点级守护。
 *
 * ## 三态语义（App 形态富化 / CLI 形态逐字不变）
 * | 文档私钥 | 回落 | 结果 |
 * |---|---|---|
 * | 非空 | 任意 | 原样（桥接显式配置恒优先） |
 * | 空 | 非空 | copy 富化（回落命中） |
 * | 空 | null/空 | 原样（未配置） |
 *
 * ## 🔴 生死线守护
 * - 富化只发生在 [Bridge.createBundle] 的 configProvider 读取层——`Bridge.config` 必须仍读文档
 *   原始值（UI 保存链不可能把回落私钥写回 bridge-config.json）；
 * - CLI 形态（fallback=null）读值与文档值逐字相等（行为逐字不变）。
 */
class XlogKeyFallbackTest {

    private fun configWithKey(key: String): BridgeConfig =
        DefaultBridgeConfig.default().let {
            it.copy(fileMessage = it.fileMessage.copy(xlogPrivateKey = key))
        }

    private fun blankKeyConfig(): BridgeConfig =
        DefaultBridgeConfig.default().let {
            it.copy(fileMessage = it.fileMessage.copy(xlogPrivateKey = ""))
        }

    // ── 三态直测（enrichXlogKey 纯函数） ──

    @Test
    fun `三态-桥接显式配置优先（非空原样返回）`() {
        val cfg = configWithKey("bridge-key")
        val enriched = Bridge.enrichXlogKey(cfg, fallback = { "host-key" })
        assertSame(cfg, enriched, "文档私钥非空时必须原实例返回（零 copy 零富化）")
        assertEquals("bridge-key", enriched.fileMessage.xlogPrivateKey)
    }

    @Test
    fun `三态-回落命中（文档空且回落非空 copy 富化）`() {
        val cfg = blankKeyConfig()
        val enriched = Bridge.enrichXlogKey(cfg, fallback = { "host-key" })
        assertEquals("host-key", enriched.fileMessage.xlogPrivateKey, "文档私钥空时回落值富化进有效私钥")
        assertNotEquals(cfg, enriched, "富化必须是 copy（原文档快照对象不被篡改）")
        // 其余字段逐位相等（只动 xlogPrivateKey 一项）
        assertEquals(cfg.copy(fileMessage = cfg.fileMessage.copy(xlogPrivateKey = "host-key")), enriched)
    }

    @Test
    fun `三态-两处都空原样返回（fallback null 与空串两形态）`() {
        val cfg = blankKeyConfig()
        assertSame(cfg, Bridge.enrichXlogKey(cfg, fallback = null), "CLI/测试形态（null）恒等返回")
        assertSame(cfg, Bridge.enrichXlogKey(cfg, fallback = { "" }), "回落源为空串同样恒等返回")
        assertEquals("", Bridge.enrichXlogKey(cfg, fallback = null).fileMessage.xlogPrivateKey)
    }

    // ── 装配级端到端（真实 assemble + 反射读 RuntimeBundle → RequestContextFactory 的 configProvider） ──

    /** 反射沿链取字段（只读；找不到即抛，避免「反射失手 ⇒ 假绿」——手法对齐 BridgeAssemblerParityTest）。 */
    private fun Any.privateField(name: String): Any? {
        var klass: Class<*>? = this::class.java
        while (klass != null) {
            klass.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it.get(this)
            }
            klass = klass.superclass
        }
        throw AssertionError("反射失败：${this::class.java.name} 无字段 '$name'（装配点结构已变，请同步更新本守护测试）")
    }

    @Suppress("UNCHECKED_CAST")
    private fun bundleConfigReader(bundle: Any): () -> BridgeConfig {
        // RuntimeBundle.intake: EventIntake → contexts: RequestContextFactory（每消息 ctx 的 configProvider 源头）
        val intake = requireNotNull(bundle.privateField("intake")) {
            "RuntimeBundle 无 'intake' 字段（EventIntake）——装配点结构已变，请同步更新本守护测试"
        }
        val factory = requireNotNull(intake.privateField("contexts")) {
            "EventIntake 无 'contexts' 字段（RequestContextFactory）——装配点结构已变，请同步更新本守护测试"
        }
        return requireNotNull(factory.privateField("configProvider")) {
            "RequestContextFactory 无 'configProvider' 字段——装配点结构已变，请同步更新本守护测试"
        } as () -> BridgeConfig
    }

    @Test
    fun `装配级-回落命中（文档空时 RuntimeBundle 读到富化私钥）`() {
        val bridge = BridgeAssembler.assemble(
            dataRoot = freshRoot("xlog-fallback-hit"),
            gatewayFactory = { SyntheticGateway() },
            defaultConfig = blankKeyConfig(),
            parentJob = Job(),
            consoleLog = false,
            xlogKeyFallback = { "host-key" },
        )
        val reader = bundleConfigReader(bridge.createBundle(blankKeyConfig()))
        assertEquals("host-key", reader().fileMessage.xlogPrivateKey, "文档私钥空时，RuntimeBundle 经 configProvider 读到回落富化值")
        // 🔴 生死线：Bridge.config（UI draft 真源）仍读文档原始值——回落绝不写回文档
        assertEquals("", bridge.config.fileMessage.xlogPrivateKey)
    }

    @Test
    fun `装配级-CLI 形态逐字不变（fallback=null 读值与文档相等）`() {
        val doc = configWithKey("doc-key")
        val bridge = BridgeAssembler.assemble(
            dataRoot = freshRoot("xlog-fallback-null"),
            gatewayFactory = { SyntheticGateway() },
            defaultConfig = doc,
            parentJob = Job(),
            consoleLog = false,
        )
        val reader = bundleConfigReader(bridge.createBundle(doc))
        assertEquals("doc-key", reader().fileMessage.xlogPrivateKey, "CLI 形态（不传回落）读值与文档逐字相等")
        assertEquals(doc, bridge.config)
    }

    // ── stamp 失效语义（回落变化 ⇒ 指纹变 ⇒ 复用不命中） ──

    @Test
    fun `stamp-回落私钥变化触发预处理指纹失效`() {
        // ReferencedMessageResolver.preprocessStamp 同构输入（autoExtract|decodeEnabled|command|key）
        val stampK1 = sha256("false|false||key-1")
        val stampK2 = sha256("false|false||key-2")
        assertNotEquals(stampK1, stampK2, "回落私钥变化 ⇒ preprocessStamp 输入串变 ⇒ 指纹必不相等（复用不命中，重跑预处理）")
        // 与文档优先语义联动：文档非空时回落不参与（指纹输入即文档值）
        assertEquals(
            sha256("false|false||doc-key"),
            sha256("false|false||" + Bridge.enrichXlogKey(configWithKey("doc-key"), fallback = { "key-1" }).fileMessage.xlogPrivateKey),
            "文档非空时富化结果恒为文档值（回落不参与指纹）",
        )
    }

    @Test
    fun `stamp-同一回落下富化幂等`() {
        val a = Bridge.enrichXlogKey(blankKeyConfig(), fallback = { "same-key" })
        val b = Bridge.enrichXlogKey(blankKeyConfig(), fallback = { "same-key" })
        assertEquals("same-key", a.fileMessage.xlogPrivateKey)
        assertEquals("same-key", b.fileMessage.xlogPrivateKey)
        assertTrue(a.fileMessage == b.fileMessage, "同回落值多次富化结果一致（幂等，无重复富化）")
    }

    private fun freshRoot(name: String): java.nio.file.Path {
        val root = java.io.File(testRepoRoot(), "build/test-tmp/$name-${System.nanoTime()}")
        Files.createDirectories(root.toPath())
        return root.toPath()
    }

    private fun sha256(raw: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
