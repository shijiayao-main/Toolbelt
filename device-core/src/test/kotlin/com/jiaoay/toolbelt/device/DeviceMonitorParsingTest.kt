package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `adb devices -l` 输出解析回归：model 提取、model 缺失回退空串、
 * offline/unauthorized/无 sid 状态过滤、daemon 提示行跳过。
 * （检测解析核随设备域升位 :core，本文件为解析面回归真源。）
 */
class DeviceMonitorParsingTest {

    @Test
    fun `parses serial and model from devices dash l output`() {
        val output = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
            ABC123XYZ              device usb:1-1 product:raven model:Pixel_6_Pro device:raven transport_id:2
        """.trimIndent()

        val devices = DeviceMonitorParser.parse(output).devices

        assertEquals(
            listOf(
                DeviceInfo(serial = "emulator-5554", model = "sdk_gphone64_x86_64"),
                DeviceInfo(serial = "ABC123XYZ", model = "Pixel_6_Pro"),
            ),
            devices,
        )
    }

    @Test
    fun `missing model property falls back to empty string`() {
        val output = """
            List of devices attached
            serial-no-model          device
        """.trimIndent()

        val devices = DeviceMonitorParser.parse(output).devices

        assertEquals(listOf(DeviceInfo(serial = "serial-no-model", model = "")), devices)
        assertEquals("serial-no-model", devices.first().displayLabel, "displayLabel 型号缺失回退串号")
    }

    @Test
    fun `offline and unauthorized devices are filtered out of devices but surface as issues`() {
        val output = """
            List of devices attached
            emulator-5554          device product:x model:M1 transport_id:1
            offline-device         offline
            unauthorized-device    unauthorized
        """.trimIndent()

        val devices = DeviceMonitorParser.parse(output).devices

        assertEquals(listOf(DeviceInfo(serial = "emulator-5554", model = "M1")), devices)
        val issues = DeviceMonitorParser.parse(output).issues
        assertEquals(listOf("offline-device", "unauthorized-device"), issues.map { it.serial })
        assertEquals(listOf("offline", "unauthorized"), issues.map { it.state })
    }

    @Test
    fun `daemon hint lines and empty output yield no devices`() {
        val output = """
            List of devices attached
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
        """.trimIndent()

        assertEquals(emptyList(), DeviceMonitorParser.parse(output).devices)
        assertEquals(emptyList(), DeviceMonitorParser.parse("List of devices attached\n").devices)
    }

    @Test
    fun `multiple devices preserve output order`() {
        val output = """
            List of devices attached
            S3          device model:Model_C
            S1          device model:Model_A
            S2          device model:Model_B
        """.trimIndent()

        val devices = DeviceMonitorParser.parse(output).devices

        assertEquals(listOf("S3", "S1", "S2"), devices.map { it.serial })
    }
}
