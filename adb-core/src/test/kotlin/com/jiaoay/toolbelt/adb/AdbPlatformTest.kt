package com.jiaoay.toolbelt.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbPlatformTest {

    @Test
    fun `detects three supported families from os name`() {
        assertEquals(AdbPlatform.WINDOWS, AdbPlatform.detect("Windows 11"))
        assertEquals(AdbPlatform.WINDOWS, AdbPlatform.detect("Windows Server 2022"))
        assertEquals(AdbPlatform.DARWIN, AdbPlatform.detect("Mac OS X"))
        assertEquals(AdbPlatform.DARWIN, AdbPlatform.detect("Darwin"))
        assertEquals(AdbPlatform.LINUX, AdbPlatform.detect("Linux"))
    }

    @Test
    fun `unknown system yields null instead of throwing`() {
        // 平台未知不得中断启动：解析端据此回退 PATH 上的 adb
        assertNull(AdbPlatform.detect("FreeBSD"))
        assertNull(AdbPlatform.detect(""))
    }

    @Test
    fun `windows carries exe suffix others do not`() {
        assertEquals("adb.exe", AdbPlatform.WINDOWS.executableName)
        assertEquals("adb", AdbPlatform.LINUX.executableName)
        assertEquals("adb", AdbPlatform.DARWIN.executableName)
        assertTrue(AdbPlatform.WINDOWS.isWindows)
        assertFalse(AdbPlatform.LINUX.isWindows)
    }

    @Test
    fun `resource keys match build asset directory names`() {
        assertEquals("windows", AdbPlatform.WINDOWS.resourceKey)
        assertEquals("linux", AdbPlatform.LINUX.resourceKey)
        assertEquals("darwin", AdbPlatform.DARWIN.resourceKey)
    }
}
