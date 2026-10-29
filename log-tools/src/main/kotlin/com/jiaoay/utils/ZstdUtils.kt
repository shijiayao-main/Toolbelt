package com.jiaoay.utils

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 */
object ZstdUtils {

    /**
     * 压缩文件
     */
    fun compressFile(
        targetFilePath: String,
        outDir: String,
        compressionLevel: Int
    ): Long {

        val file = File(targetFilePath)
        val outFile = File(outDir, file.name + ".zs")
        var numBytes = 0L
        val inBuffer: ByteBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024) //要被压缩的字节缓冲区
        var compressedBuffer: ByteBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024) //压缩后放置到该缓冲区

        RandomAccessFile(file, "r").use { inRaFile ->
            RandomAccessFile(outFile, "rw").use { outRaFile ->
                inRaFile.channel.use { inChannel ->
                    outRaFile.channel.use { outChannel ->
                        inBuffer.clear()
                        // 当文件还有字节未压缩时
                        while (inChannel.read(inBuffer) > 0) {
                            // 反转缓冲区的读写模式
                            inBuffer.flip()

                            // 计算所需的压缩缓冲区大小
                            val maxCompressedSize =
                                Zstd.compressBound(inBuffer.remaining().toLong()).toInt()

                            // 如果当前缓冲区不够大，重新分配
                            if (compressedBuffer.capacity() < maxCompressedSize) {
                                compressedBuffer = ByteBuffer.allocateDirect(maxCompressedSize)
                            }

                            compressedBuffer.clear()

                            // 将inBuffer的0-inBuffer.limit()压缩到compressedBuffer的0-compressedBuffer.capacity()。
                            val compressedSize = Zstd.compressDirectByteBuffer(
                                compressedBuffer,
                                0,
                                compressedBuffer.capacity(),
                                inBuffer,
                                0,
                                inBuffer.limit(),
                                compressionLevel
                            )

                            numBytes += compressedSize
                            compressedBuffer.position(compressedSize.toInt())
                            compressedBuffer.flip()
                            // 把压缩后得到的缓冲区写入文件输出通道
                            outChannel.write(compressedBuffer)
                            inBuffer.clear()
                        }
                    }
                }
            }
        }

        return numBytes
    }

    /**
     * 解压文件
     */
    fun decompressFile(targetFilePath: String, outFilePath: String) {
        // 待解压文件
        val inputFile = File(targetFilePath)
        // 解压后文件
        val outputFile = File(outFilePath)

        val buffer = ByteArray(1024 * 1024 * 8)
        val fileInputStream = FileInputStream(inputFile)
        val fileOutputStream = FileOutputStream(outputFile)
        // 将文件输入流复制到zs
        val zstdInputStream = ZstdInputStream(fileInputStream)
        fileInputStream.use {
            fileOutputStream.use {
                zstdInputStream.use {
                    while (true) {
                        // zs中重写了read方法，该方法包含解压过程，将0-buffer.length读入buffer
                        val count = zstdInputStream.read(
                            buffer,
                            0,
                            buffer.size
                        )
                        if (count == -1) {
                            break
                        }
                        fileOutputStream.write(buffer, 0, count) //将buffer中的0-count写入文件输出流
                    }
                    fileOutputStream.flush()
                }
            }
        }
    }
}