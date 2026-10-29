package com.jiaoay.xlog.codec

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/** zlib(raw) 解压 */
internal object ZlibDecompressor {

    fun decompress(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val outputStream = InflaterOutputStream(byteArrayOutputStream, Inflater(true))
        outputStream.write(data)
        outputStream.close()
        return byteArrayOutputStream.toByteArray()
    }
}

/** zstd 解压, 循环读取以支持任意大小的解压结果 */
internal object ZstdDecompressor {

    fun decompress(data: ByteArray): ByteArray {
        val byteArrayInputStream = ByteArrayInputStream(data)
        val byteArrayOutputStream = ByteArrayOutputStream()
        ZstdInputStream(byteArrayInputStream).use { zstdInputStream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val bytesRead = zstdInputStream.read(buffer, 0, buffer.size)
                if (bytesRead < 0) {
                    break
                }
                byteArrayOutputStream.write(buffer, 0, bytesRead)
            }
        }
        return byteArrayOutputStream.toByteArray()
    }
}
