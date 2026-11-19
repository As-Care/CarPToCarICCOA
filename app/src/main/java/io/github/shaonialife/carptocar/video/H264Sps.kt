package io.github.shaonialife.carptocar.video

import java.io.ByteArrayOutputStream

/** Reads and adjusts the display crop in an H.264 SPS. */
internal object H264Sps {

    /** 从**单个 SPS NAL**（首字节是 NAL 头 0x67，不含起始码）解析宽高。 */
    fun parseNal(nal: ByteArray): Pair<Int, Int>? {
        if (nal.size < 8) return null
        return runCatching {
            // 跳过 NAL 头，并剔除 emulation prevention bytes（00 00 03 → 00 00）。
            // 漏这一步会让 bit 流整体错位，解出 272x112 之类的垃圾值。
            val rbsp = stripEmulation(nal, 1, nal.size)
            parseRbsp(rbsp).dimensions
        }.getOrNull()
    }

    /** Increase the existing bottom crop of a progressive 4:2:0 SPS by [pixels]. */
    fun cropBottom(nal: ByteArray, pixels: Int): ByteArray? {
        if (nal.size < 8 || (nal[0].toInt() and 0x1f) != 7) return null
        return runCatching {
            val rbsp = stripEmulation(nal, 1, nal.size)
            val sps = parseRbsp(rbsp)
            val crop = sps.bottomCrop ?: return null
            if (!sps.progressive420 || pixels <= 0 || pixels % 2 != 0 || pixels >= sps.dimensions.second) return null

            // Copy through rbsp_stop_one_bit, then zero-pad to the new byte boundary.
            val lastByte = rbsp.indexOfLast { it != 0.toByte() }
            val bitEnd = (lastByte + 1) * 8 - Integer.numberOfTrailingZeros(rbsp[lastByte].toInt() and 0xff)
            if (crop.end >= bitEnd) return null
            val code = crop.offset + pixels / 2 + 1
            val codeBits = Integer.SIZE - Integer.numberOfLeadingZeros(code)
            val newBitCount = crop.start + 2 * codeBits - 1 + bitEnd - crop.end
            val edited = ByteArray((newBitCount + 7) / 8)
            var position = 0
            fun writeBit(bit: Int) {
                val byte = position ushr 3
                edited[byte] = (edited[byte].toInt() or (bit shl (7 - (position and 7)))).toByte()
                position++
            }

            val reader = BitReader(rbsp)
            repeat(crop.start) { writeBit(reader.u1()) }
            position += codeBits - 1 // Exp-Golomb leading zeros.
            for (bit in codeBits - 1 downTo 0) writeBit((code ushr bit) and 1)
            repeat(crop.end - crop.start) { reader.u1() }
            repeat(bitEnd - crop.end) { writeBit(reader.u1()) }

            escapeNal(nal[0], edited).takeIf {
                parseNal(it) == (sps.dimensions.first to sps.dimensions.second - pixels)
            }
        }.getOrNull()
    }

    private fun escapeNal(header: Byte, rbsp: ByteArray): ByteArray {
        val nal = ByteArrayOutputStream(rbsp.size + 1)
        nal.write(header.toInt())
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xff
            if (zeros == 2 && value <= 3) {
                nal.write(3)
                zeros = 0
            }
            nal.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return nal.toByteArray()
    }

    private fun stripEmulation(
        src: ByteArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val out = ByteArray(to - from)
        var o = 0
        var zeros = 0
        for (i in from until to) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[o++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    private class BitReader(private val data: ByteArray) {
        var bitPos = 0
            private set

        fun u1(): Int {
            val byteIndex = bitPos ushr 3
            if (byteIndex >= data.size) throw IndexOutOfBoundsException()
            val bit = 7 - (bitPos and 7)
            bitPos++
            return (data[byteIndex].toInt() ushr bit) and 1
        }

        fun u(n: Int): Int {
            var value = 0
            repeat(n) { value = (value shl 1) or u1() }
            return value
        }

        /** 无符号指数哥伦布。 */
        fun ue(): Int {
            var leadingZeros = 0
            while (u1() == 0) {
                leadingZeros++
                if (leadingZeros > 32) throw IllegalStateException("ue overflow")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + u(leadingZeros)
        }

        /** 有符号指数哥伦布。 */
        fun se(): Int {
            val v = ue()
            return if (v % 2 == 0) -(v / 2) else (v + 1) / 2
        }
    }

    private data class BottomCrop(val offset: Int, val start: Int, val end: Int)

    private data class ParsedSps(
        val dimensions: Pair<Int, Int>,
        val bottomCrop: BottomCrop?,
        val progressive420: Boolean,
    )

    private fun parseRbsp(rbsp: ByteArray): ParsedSps {
        val r = BitReader(rbsp)
        val profileIdc = r.u(8)
        r.u(8) // constraint_set0..5 + reserved（8 位，多读一位会让后面全部错位）
        r.u(8) // level_idc
        r.ue() // seq_parameter_set_id

        var chromaFormatIdc = 1
        var separateColourPlane = false
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) separateColourPlane = r.u1() == 1
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.u1() // qpprime_y_zero_transform_bypass_flag
            if (r.u1() == 1) { // seq_scaling_matrix_present_flag
                val count = if (chromaFormatIdc == 3) 12 else 8
                for (i in 0 until count) {
                    if (r.u1() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }

        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                r.u1() // delta_pic_order_always_zero_flag
                r.se() // offset_for_non_ref_pic
                r.se() // offset_for_top_to_bottom_field
                val cycle = r.ue()
                repeat(cycle) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.u1() // gaps_in_frame_num_value_allowed_flag

        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.u1()
        if (frameMbsOnly == 0) r.u1() // mb_adaptive_frame_field_flag
        r.u1() // direct_8x8_inference_flag

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var bottomCrop: BottomCrop? = null
        if (r.u1() == 1) { // frame_cropping_flag
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            val start = r.bitPos
            val offset = r.ue()
            bottomCrop = BottomCrop(offset, start, r.bitPos)
        }

        val subWidthC = if (chromaFormatIdc == 3 || separateColourPlane) 1 else 2
        val subHeightC = when {
            chromaFormatIdc == 1 -> 2
            chromaFormatIdc == 3 || separateColourPlane -> 1
            else -> 1 // 4:2:2 已经是 1，其它按 1 处理
        }
        val width = widthMbs * 16 - (cropLeft + cropRight) * subWidthC
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 -
            (cropTop + (bottomCrop?.offset ?: 0)) * subHeightC * (2 - frameMbsOnly)
        if (width <= 0 || height <= 0) throw IllegalStateException("bad sps size")
        return ParsedSps(width to height, bottomCrop, frameMbsOnly == 1 && chromaFormatIdc == 1)
    }

    private fun skipScalingList(
        r: BitReader,
        size: Int,
    ) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val delta = r.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private val HIGH_PROFILES = setOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135,
    )
}
