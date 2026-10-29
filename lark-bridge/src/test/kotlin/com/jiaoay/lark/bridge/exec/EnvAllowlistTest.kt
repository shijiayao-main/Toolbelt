package com.jiaoay.lark.bridge.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BC §4.9 · BC-419/420/421] EnvAllowlist：基础白名单 11 键精确清单（含 http_proxy/https_proxy/no_proxy 三个小写变体）
 * + Windows 平台必需键 11 键强制并入（不可被用户配置移除）+ retainAllowed 就地过滤语义。
 * 🔴 envAllowlist 默认值取自旧实现 SandboxedProcessFactory.kt:57-61 的实测 11 键，**不是方案的 8 键**（CDS §16.1 修正）。
 */
class EnvAllowlistTest {

    @Test
    fun `BASE_ENV_KEYS 恰为 11 键且含三个小写代理变体（BC-420 零回归锚点）`() {
        val expected = linkedSetOf(
            "PATH", "HOME", "LANG", "LC_ALL", "TZ",
            "http_proxy", "https_proxy", "no_proxy",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
        )
        assertEquals(expected, EnvAllowlist.BASE_ENV_KEYS)
        assertEquals(11, EnvAllowlist.BASE_ENV_KEYS.size)
        assertTrue(EnvAllowlist.BASE_ENV_KEYS.containsAll(listOf("http_proxy", "https_proxy", "no_proxy")))
        assertTrue(EnvAllowlist.BASE_ENV_KEYS.containsAll(listOf("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")))
    }

    @Test
    fun `WINDOWS_REQUIRED_ENV_KEYS 恰为 11 键系统必需键（BC-421）`() {
        val expected = linkedSetOf(
            "SystemRoot", "SystemDrive", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "APPDATA", "LOCALAPPDATA", "COMSPEC", "PATHEXT", "TEMP", "TMP",
        )
        assertEquals(expected, EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS)
        assertEquals(11, EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS.size)
        // 不含凭据类变量
        assertFalse(EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS.any { it.contains("SECRET") || it.contains("TOKEN") || it.contains("KEY") && it != "PATHEXT" })
    }

    @Test
    fun `effectiveBaseKeys - Unix 与 BASE 逐键相等`() {
        assertEquals(EnvAllowlist.BASE_ENV_KEYS, EnvAllowlist.effectiveBaseKeys(isWindows = false))
    }

    @Test
    fun `effectiveBaseKeys - Windows 为 BASE 并平台必需键（22 键）`() {
        val keys = EnvAllowlist.effectiveBaseKeys(isWindows = true)
        assertEquals(22, keys.size)
        assertTrue(keys.containsAll(EnvAllowlist.BASE_ENV_KEYS))
        assertTrue(keys.containsAll(EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS))
    }

    @Test
    fun `resolveAllowed - Unix 全凭用户配置无强制键`() {
        val allowed = EnvAllowlist.resolveAllowed(listOf("PATH", "HOME"), isWindows = false)
        assertEquals(setOf("PATH", "HOME"), allowed)
    }

    @Test
    fun `resolveAllowed - Windows 必需键强制并入不可被用户配置移除（BC-421）`() {
        // 用户配置里刻意不含任何 Windows 必需键
        val allowed = EnvAllowlist.resolveAllowed(listOf("PATH"), isWindows = true)
        assertTrue(allowed.containsAll(EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS))
        assertTrue(allowed.contains("PATH"))
        assertTrue(allowed.contains("SystemRoot"))
        assertTrue(allowed.contains("COMSPEC"))
    }

    @Test
    fun `retainAllowed - 就地过滤只保留命中白名单的键`() {
        val env = mutableMapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/home/u",
            "AWS_SECRET_ACCESS_KEY" to "leak-me",
            "GITHUB_TOKEN" to "leak-me-too",
            "RANDOM_VAR" to "x",
        )
        EnvAllowlist.retainAllowed(env, setOf("PATH", "HOME"))
        assertEquals(setOf("PATH", "HOME"), env.keys)
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"))
        assertFalse(env.containsKey("GITHUB_TOKEN"))
        assertFalse(env.containsKey("RANDOM_VAR"))
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun `retainAllowed - 空白名单清空全部键（沙箱化）`() {
        val env = mutableMapOf("PATH" to "/usr/bin", "HOME" to "/h")
        EnvAllowlist.retainAllowed(env, emptySet())
        assertTrue(env.isEmpty())
    }

    // ==================== 🔴 2026-09-17 审计修复回归（Windows 键大小写不敏感） ====================

    /**
     * Windows 环境块保留系统原始拼写（`Path`/`SystemRoot`），白名单字面是 `PATH`——
     * 旧 `keys.retainAll(allowed)` 是 case-sensitive ⇒ `Path` 被剥掉 ⇒ 子进程丢 PATH。
     * 修复 = Windows 侧 ignoreCase 匹配；Unix 侧保持 case-sensitive（环境变量本就区分大小写）。
     */
    @Test
    fun `retainAllowed - Windows 下 Path 拼写命中 PATH 白名单不剥除`() {
        val env = mutableMapOf(
            "Path" to "C:\\Windows\\System32",     // Windows 环境块的真实拼写
            "SystemRoot" to "C:\\Windows",          // 不在本组白名单（仅 PATH/HOME）——应剥除
            "AWS_SECRET_ACCESS_KEY" to "leak",
        )
        EnvAllowlist.retainAllowed(env, setOf("PATH", "HOME"), isWindows = true)
        assertEquals(setOf("Path"), env.keys, "Path 按 PATH 白名单 ignoreCase 命中保留；其余剥除")
    }

    @Test
    fun `retainAllowed - Windows 平台必需键大小写变体保留`() {
        val env = mutableMapOf(
            "path" to "x", "USERPROFILE" to "x", "userprofile" to "y",
            "APpDaTa" to "z",
        )
        EnvAllowlist.retainAllowed(env, EnvAllowlist.WINDOWS_REQUIRED_ENV_KEYS, isWindows = true)
        assertEquals(setOf("USERPROFILE", "userprofile", "APpDaTa"), env.keys,
            "白名单键的任意大小写变体都应保留；不在白名单的 path（若系统块存 path 拼写）被剥除")
    }

    @Test
    fun `retainAllowed - Unix 侧保持大小写敏感`() {
        val env = mutableMapOf("Path" to "x", "PATH" to "/usr/bin")
        EnvAllowlist.retainAllowed(env, setOf("PATH"), isWindows = false)
        assertEquals(setOf("PATH"), env.keys, "Unix 环境变量区分大小写，Path ≠ PATH")
    }

    @Test
    fun `isWindows 探测与 os name 一致`() {
        val expected = System.getProperty("os.name").lowercase().contains("windows")
        assertEquals(expected, EnvAllowlist.isWindows())
    }
}
