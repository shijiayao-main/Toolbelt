package com.jiaoay.easy_dev_toolbox.page.screencast

/**
 * H.264 Annex-B ↔ AVCC 码流换算纯函数（录像 mux 的容器前置处理，零 IO 零依赖）。
 *
 * MediaCodec/scrcpy 链路产出 **Annex-B**（00 00 00 01 起始码分隔的 NALU 序列），
 * MP4 容器要求 **AVCC**（4 字节大端长度前缀）且 SPS/PPS 收进 avcC extradata——
 * 换算即录像 mux（[AvformatScreencastRecorder]）的正确性前提：
 * - [splitNalus]：Annex-B 切 NALU（3/4 字节起始码同识，起始码不进产物）；
 * - [buildAvcC]：SPS/PPS → avcC 框（ISO/IEC 14496-15 `AVCDecoderConfigurationRecord`，
 *   profile/compat/level 字节取自 SPS 头三字节，NALU 长度字段 4 字节与帧侧转换一致）；
 * - [annexbToAvcc]：单个访问单元（一帧的 NALU 序列）→ AVCC 形态。
 *
 * 帧序不变量：payload 中的 NALU 顺序与切分结果一一对应，转换不增删不重排 NALU。
 */
object H264Annexb {

    /** NALU 单元类型（`nal_unit_type`，头字节低 5 位）：1-5 VCL（5 = IDR）、7 SPS、8 PPS */
    fun naluType(nalu: ByteArray): Int = nalu[0].toInt() and 0x1F

    /** 码流是否含可解析的 SPS 与 PPS（avcC 构造前提；录像起点 config 校验用） */
    fun hasSpsPps(configAnnexb: ByteArray): Boolean {
        var sps = false
        var pps = false
        for (nalu in splitNalus(configAnnexb)) {
            when (naluType(nalu)) {
                7 -> sps = true
                8 -> pps = true
            }
        }
        return sps && pps
    }

    /**
     * Annex-B 切 NALU（起始码剥离）。识别 3 字节（00 00 01）与 4 字节（00 00 00 01）
     * 起始码——4 字节码经 3 字节扫描自然命中，但前导零必须归入下一码而非上一 NALU
     * 尾部，否则 NALU 载荷混入起始码残留（AVCC 长度前缀换算即错位）。
     */
    fun splitNalus(data: ByteArray): List<ByteArray> {
        val marks = ArrayList<Pair<Int, Int>>() // contentBegin to codeBegin
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && (data[i + 2].toInt() and 0xFF) == 1) {
                val codeBegin = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                marks.add((i + 3) to codeBegin)
                i += 3
            } else {
                i++
            }
        }
        val nalus = ArrayList<ByteArray>(marks.size)
        for (k in marks.indices) {
            val begin = marks[k].first
            val end = if (k + 1 < marks.size) marks[k + 1].second else data.size
            if (end > begin) nalus.add(data.copyOfRange(begin, end))
        }
        return nalus
    }

    /**
     * SPS/PPS（Annex-B）→ avcC extradata 框。结构（14496-15）：
     * `configurationVersion(1) | profile | compat | level | 0xFF(长度4B) | 0xE|spsCount
     * | [len16 sps]* | ppsCount | [len16 pps]*`——profile/compat/level 逐字节取自 SPS
     * 头三字节（SPS NALU = 头字节 + 该三字节 + 参数），与码流内容自洽。
     * 无 SPS/PPS 时抛 [IllegalArgumentException]（调用方前置校验是契约，此处防御）。
     */
    fun buildAvcC(configAnnexb: ByteArray): ByteArray {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nalu in splitNalus(configAnnexb)) {
            when (naluType(nalu)) {
                7 -> if (sps == null) sps = nalu
                8 -> if (pps == null) pps = nalu
            }
        }
        val spsBytes = sps ?: throw IllegalArgumentException("config 载荷不含 SPS，无法构造 avcC")
        val ppsBytes = pps ?: throw IllegalArgumentException("config 载荷不含 PPS，无法构造 avcC")
        val out = java.io.ByteArrayOutputStream(spsBytes.size + ppsBytes.size + 8)
        out.write(0x01)
        out.write(spsBytes[1].toInt() and 0xFF)
        out.write(spsBytes[2].toInt() and 0xFF)
        out.write(spsBytes[3].toInt() and 0xFF)
        out.write(0xFF) // 6 位 reserved + lengthSizeMinusOne=3（长度字段 4 字节）
        out.write(0xE0 or 1) // 3 位 reserved + spsCount=1
        writeLengthPrefixed(out, spsBytes)
        out.write(1) // ppsCount
        writeLengthPrefixed(out, ppsBytes)
        return out.toByteArray()
    }

    /** 单个访问单元 Annex-B → AVCC（逐 NALU 加 4 字节大端长度前缀；NALU 序不变） */
    fun annexbToAvcc(accessUnitAnnexb: ByteArray): ByteArray {
        val nalus = splitNalus(accessUnitAnnexb)
        require(nalus.isNotEmpty()) { "空载荷不是合法的 Annex-B 访问单元" }
        val out = java.io.ByteArrayOutputStream(accessUnitAnnexb.size)
        for (nalu in nalus) {
            val length = nalu.size
            out.write((length ushr 24) and 0xFF)
            out.write((length ushr 16) and 0xFF)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write(nalu)
        }
        return out.toByteArray()
    }

    private fun writeLengthPrefixed(out: java.io.ByteArrayOutputStream, nalu: ByteArray) {
        out.write((nalu.size ushr 8) and 0xFF)
        out.write(nalu.size and 0xFF)
        out.write(nalu)
    }
}
