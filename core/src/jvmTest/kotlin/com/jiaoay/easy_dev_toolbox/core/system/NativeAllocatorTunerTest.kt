package com.jiaoay.easy_dev_toolbox.core.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NativeAllocatorTuner] 行为面：平台门控纯函数 + install 的启动契约
 * （幂等、不抛异常、glibc Linux 上主修复面生效）。
 */
class NativeAllocatorTunerTest {

    @Test
    fun `platform gate matches linux only`() {
        assertFalse(NativeAllocatorTuner.isSupportedPlatform(null))
        assertFalse(NativeAllocatorTuner.isSupportedPlatform("Mac OS X"))
        assertFalse(NativeAllocatorTuner.isSupportedPlatform("Windows 11"))
        assertTrue(NativeAllocatorTuner.isSupportedPlatform("Linux"))
        assertTrue(NativeAllocatorTuner.isSupportedPlatform("linux"))
    }

    @Test
    fun `install never throws and is idempotent`() {
        // 启动契约：任何平台、任何次数调用都必须安全返回（调参失败只降级不抛）
        NativeAllocatorTuner.install()
        NativeAllocatorTuner.install()
    }

    @Test
    fun `install succeeds on glibc linux`() {
        // 本仓交付面 = Linux x86-64（glibc）；测试机即目标平台时主修复面必须生效
        if (NativeAllocatorTuner.isSupportedPlatform(System.getProperty("os.name"))) {
            assertTrue(NativeAllocatorTuner.install())
        }
    }
}
