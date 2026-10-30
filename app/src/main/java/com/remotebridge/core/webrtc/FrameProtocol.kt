package com.remotebridge.core.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * بروتوكول تأطير الرسائل فوق قناة بيانات WebRTC مع دعم الموثوقية (ACK/NACK/Checksum).
 *
 * تنسيق الإطار المحدث (22 بايت رأس + حمولة):
 *   [1 بايت]   type       : 0=JSON، 1=Binary، 2=ACK، 3=NACK، 4=Ping، 5=Pong
 *   [4 بايت]   messageId  : معرّف الرسالة (لمطابقة الردود والملفات)
 *   [4 بايت]   seq        : رقم القطعة (يبدأ من 0)
 *   [4 بايت]   total      : إجمالي القطع (0 لرسائل التحكم أحادية)
 *   [1 بايت]   flags      : بتات flags (0x01=يحتاج تأكيد، 0x02=مضغوط)
 *   [4 بايت]   checksum   : CRC32 لحمل البيانات للتحقق من السلامة
 *   [4 بايت]   payloadLen : طول الحمولة
 *   [N بايت]   payload
 *
 * رأس فعلي = 1+4+4+4+1+4+4 = 22 بايت، الحمولة تبدأ عند الإزاحة 22.
 * القناة مرتبة (ordered = true) لذا تصل القطع بالترتيب نفسه المُرسل.
 */
object FrameProtocol {
    const val TYPE_JSON: Byte = 0
    const val TYPE_BINARY: Byte = 1
    const val TYPE_ACK: Byte = 2
    const val TYPE_NACK: Byte = 3
    const val TYPE_PING: Byte = 4
    const val TYPE_PONG: Byte = 5

    const val FLAG_NEEDS_ACK: Byte = 0x01
    const val FLAG_COMPRESSED: Byte = 0x02

    const val HEADER_SIZE = 22

    /** حجم القطعة الواحدة — أقل من حد SCTP لضمان الوصول الموثوق. */
    const val MAX_PAYLOAD = 16 * 1024

    /** أقصى عدد محاولات إعادة إرسال */
    const val MAX_RETRIES = 3

    /** مهلة انتظار ACK بالمللي ثانية */
    const val ACK_TIMEOUT_MS = 5000

    data class Frame(
        val type: Byte,
        val messageId: Int,
        val seq: Int,
        val total: Int,
        val flags: Byte,
        val checksum: Int,
        val payload: ByteBuffer
    )

    /**
     * حساب CRC32 للتحقق من سلامة البيانات.
     */
    fun calculateChecksum(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    fun calculateChecksum(buffer: ByteBuffer): Int {
        val crc = java.util.zip.CRC32()
        val dup = buffer.duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        crc.update(arr)
        return crc.value.toInt()
    }

    /**
     * التحقق من أن إطار وارد intact وغير تالف.
     */
    fun verifyChecksum(frame: Frame): Boolean {
        val payloadArr = ByteArray(frame.payload.remaining())
        frame.payload.duplicate().get(payloadArr)
        return calculateChecksum(payloadArr) == frame.checksum
    }

    /**
     * يحلّل إطاراً وارداً. يعيد null إذا كان غير مكتمل أو تالف.
     */
    fun parse(buffer: ByteBuffer): Frame? {
        if (buffer.remaining() < HEADER_SIZE) return null
        val dup = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        val type = dup.get()
        val messageId = dup.int
        val seq = dup.int
        val total = dup.int
        val flags = dup.get()
        val checksum = dup.int
        val len = dup.int
        if (type != TYPE_JSON && type != TYPE_BINARY && type != TYPE_ACK && type != TYPE_NACK && type != TYPE_PING && type != TYPE_PONG) return null
        if (len < 0 || dup.remaining() < len) return null

        buffer.position(buffer.position() + HEADER_SIZE + len)
        val payload = dup.slice().limit(len) as ByteBuffer
        return Frame(type, messageId, seq, total, flags, checksum, payload)
    }

    /**
     * يبني إطار تحكم JSON أحادي القطعة مع دعم ACK.
     */
    fun buildJsonFrame(messageId: Int, json: String, needsAck: Boolean = true): ByteBuffer {
        val payload = json.toByteArray(Charsets.UTF_8)
        val flags = if (needsAck) FLAG_NEEDS_ACK else 0x00.toByte()
        val checksum = calculateChecksum(payload)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_JSON)
        buffer.putInt(messageId)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(flags)
        buffer.putInt(checksum)
        buffer.putInt(payload.size)
        buffer.put(payload)
        buffer.flip()
        return buffer
    }

    /**
     * يبني إطار ACK لتأكيد استلام رسالة.
     */
    fun buildAckFrame(messageId: Int, seq: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_ACK)
        buffer.putInt(messageId)
        buffer.putInt(seq)
        buffer.putInt(0)
        buffer.put(0x00.toByte())
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.flip()
        return buffer
    }

    /**
     * يبني إطار NACK لطلب إعادة إرسال رسالة.
     */
    fun buildNackFrame(messageId: Int, seq: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_NACK)
        buffer.putInt(messageId)
        buffer.putInt(seq)
        buffer.putInt(0)
        buffer.put(0x00.toByte())
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.flip()
        return buffer
    }

    /**
     * يبني قطعة ثنائية واحدة من ملف مع دعم ACK.
     */
    fun buildBinaryChunk(
        messageId: Int,
        seq: Int,
        total: Int,
        chunk: ByteArray,
        offset: Int,
        length: Int,
        needsAck: Boolean = false
    ): ByteBuffer {
        val payload = chunk.copyOfRange(offset, offset + length)
        val flags = if (needsAck) FLAG_NEEDS_ACK else 0x00.toByte()
        val checksum = calculateChecksum(payload)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + length)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_BINARY)
        buffer.putInt(messageId)
        buffer.putInt(seq)
        buffer.putInt(total)
        buffer.put(flags)
        buffer.putInt(checksum)
        buffer.putInt(length)
        buffer.put(payload)
        buffer.flip()
        return buffer
    }

    /**
     * يبني إطار Ping لنبض القلب فوق قناة البيانات.
     */
    fun buildPingFrame(messageId: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_PING)
        buffer.putInt(messageId)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(0x00.toByte())
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.flip()
        return buffer
    }

    /**
     * يبني إطار Pong كرد على Ping.
     */
    fun buildPongFrame(messageId: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_PONG)
        buffer.putInt(messageId)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(0x00.toByte())
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.flip()
        return buffer
    }

    /**
     * يحسب عدد القطوع اللازمة لملف بحجم معين (لا يقل عن 1).
     */
    fun totalChunks(size: Long): Int {
        return ((size + MAX_PAYLOAD - 1) / MAX_PAYLOAD).toInt().coerceAtLeast(1)
    }
}
