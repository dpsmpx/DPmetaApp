package com.dpsmpx.dpai.lsp.jdwp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Связь по JDWP (D-161): рукопожатие, пакеты команд и ответов, события ВМ —
 * слушателю из потока чтения. Пакет длиннее [MAX_PACKET], обрыв или пакет не по
 * протоколу — остановка связи целиком (как у DAP, D-159). Пакет: длина (4 байта,
 * с заголовком), номер (4), флаги (1; `0x80` — ответ), у команды — набор и номер
 * команды, у ответа — код ошибки (2), затем данные.
 */
class JdwpPeer(input: InputStream, private val output: OutputStream, private val handler: Handler) {
    interface Handler {
        /** Команда ВМ к отладчику — почти всегда `Event.Composite` (64/100). */
        fun command(set: Int, cmd: Int, data: ByteArray)

        fun closed(reason: Diagnostic)
    }

    private val input = DataInputStream(input)
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Outcome<ByteArray>>>()
    private val open = AtomicBoolean(true)
    private val writeLock = Any()

    val isOpen: Boolean get() = open.get()

    /** Рукопожатие и поток чтения. */
    fun start(): Outcome<Unit> {
        try {
            synchronized(writeLock) { output.write(HANDSHAKE); output.flush() }
            val back = ByteArray(HANDSHAKE.size)
            input.readFully(back)
            if (!back.contentEquals(HANDSHAKE)) return fail("ВМ ответила не рукопожатием JDWP")
        } catch (e: IOException) {
            return fail("рукопожатие JDWP не прошло: ${e.javaClass.simpleName}")
        }
        Thread(::read, "dpai-jdwp").apply { isDaemon = true }.start()
        return Outcome.success(Unit)
    }

    private fun fail(message: String): Outcome.Failure {
        close(Diagnostic.error(DiagCode.JDWP_CONNECTION_LOST, message))
        return Outcome.failure(DiagCode.JDWP_CONNECTION_LOST, message)
    }

    /** Команда [set]/[cmd] с данными [data]; ответ — данные ответа или отказ с кодом ошибки JDWP. */
    fun request(set: Int, cmd: Int, data: ByteArray, timeout: Duration): Outcome<ByteArray> {
        if (!open.get()) return Outcome.failure(DiagCode.JDWP_CONNECTION_LOST, "связь с ВМ закрыта", "jdwp $set/$cmd")
        val id = ids.getAndIncrement()
        val future = CompletableFuture<Outcome<ByteArray>>()
        pending[id] = future
        // Связь могла закрыться между проверкой и записью в [pending]: ответа тогда не будет.
        if (!open.get()) {
            pending.remove(id)
            return Outcome.failure(DiagCode.JDWP_CONNECTION_LOST, "связь с ВМ закрыта", "jdwp $set/$cmd")
        }
        try {
            val packet = ByteArrayOutputStream(11 + data.size)
            val o = java.io.DataOutputStream(packet)
            o.writeInt(11 + data.size); o.writeInt(id); o.writeByte(0); o.writeByte(set); o.writeByte(cmd); o.write(data)
            synchronized(writeLock) { output.write(packet.toByteArray()); output.flush() }
        } catch (e: IOException) {
            pending.remove(id)
            close(Diagnostic.error(DiagCode.JDWP_CONNECTION_LOST, "запись в ВМ не прошла: ${e.javaClass.simpleName}"))
            return Outcome.failure(DiagCode.JDWP_CONNECTION_LOST, "связь с ВМ оборвалась", "jdwp $set/$cmd")
        }
        return try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(id)
            Outcome.failure(DiagCode.JDWP_TIMEOUT, "ВМ не ответила за ${timeout.inWholeSeconds} с", "jdwp $set/$cmd")
        }
    }

    private fun read() {
        try {
            while (open.get()) {
                val length = input.readInt()
                if (length < 11 || length > MAX_PACKET) {
                    close(Diagnostic.error(DiagCode.JDWP_REPLY_INVALID, "пакет JDWP длиной $length — не по протоколу"))
                    return
                }
                val id = input.readInt()
                val flags = input.readUnsignedByte()
                val body = ByteArray(length - 11)
                if (flags and REPLY != 0) {
                    val error = input.readUnsignedShort()
                    input.readFully(body)
                    pending.remove(id)?.complete(
                        if (error == 0) Outcome.success(body) else Outcome.failure(DiagCode.JDWP_REQUEST_FAILED, "ВМ отказала: ${errorText(error)}", "jdwp error $error"),
                    )
                } else {
                    val set = input.readUnsignedByte()
                    val cmd = input.readUnsignedByte()
                    input.readFully(body)
                    runCatching { handler.command(set, cmd, body) }
                }
            }
        } catch (_: EOFException) {
            close(Diagnostic.error(DiagCode.JDWP_CONNECTION_LOST, "ВМ закрыла связь"))
        } catch (e: IOException) {
            close(Diagnostic.error(DiagCode.JDWP_CONNECTION_LOST, "связь с ВМ оборвалась: ${e.javaClass.simpleName}"))
        }
    }

    fun close(reason: Diagnostic = Diagnostic.error(DiagCode.JDWP_CONNECTION_LOST, "связь закрыта отладчиком")) {
        if (!open.compareAndSet(true, false)) return
        runCatching { output.close() }
        runCatching { input.close() }
        val failure = Outcome.Failure(listOf(reason))
        pending.values.forEach { it.complete(failure) }
        pending.clear()
        handler.closed(reason)
    }

    companion object {
        val HANDSHAKE: ByteArray = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)
        const val REPLY = 0x80
        const val MAX_PACKET = 16 * 1024 * 1024

        /** Частые коды ошибок JDWP — словами; прочие — числом. */
        fun errorText(code: Int): String = when (code) {
            10 -> "нет такого потока"
            13 -> "поток не остановлен"
            20 -> "нет такого объекта"
            21 -> "класс не загружен"
            22 -> "класс ещё не подготовлен"
            23 -> "нет такого метода"
            24 -> "неверное место"
            35 -> "неверный номер кадра или слота"
            41 -> "нет сведений о переменных (сборка без отладочных сведений)"
            99 -> "не поддерживается этой ВМ"
            101 -> "нет сведений: метод без строк или кода"
            112 -> "ВМ умерла"
            else -> "код $code"
        }
    }
}

/** Данные JDWP: big-endian, размеры идентификаторов — из `IDSizes`. */
internal class JdwpOut(private val sizes: JdwpSizes) {
    private val buffer = ByteArrayOutputStream()
    private val out = java.io.DataOutputStream(buffer)
    fun byte(v: Int) = apply { out.writeByte(v) }
    fun int(v: Int) = apply { out.writeInt(v) }
    fun long(v: Long) = apply { out.writeLong(v) }
    fun bool(v: Boolean) = apply { out.writeByte(if (v) 1 else 0) }
    fun string(v: String) = apply { val b = v.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
    fun objectId(v: Long) = id(v, sizes.objectId)
    fun typeId(v: Long) = id(v, sizes.typeId)
    fun methodId(v: Long) = id(v, sizes.methodId)
    fun frameId(v: Long) = id(v, sizes.frameId)
    fun fieldId(v: Long) = id(v, sizes.fieldId)
    private fun id(v: Long, size: Int) = apply { for (i in size - 1 downTo 0) out.writeByte((v ushr (8 * i)).toInt() and 0xFF) }
    fun location(l: JdwpLocation) = apply { byte(l.typeTag); typeId(l.classId); methodId(l.methodId); long(l.index) }
    fun bytes(): ByteArray = buffer.toByteArray()
}

internal class JdwpIn(private val data: ByteArray, private val sizes: JdwpSizes) {
    private var at = 0
    private fun need(n: Int) { if (at + n > data.size) throw IOException("ответ JDWP короче ожидаемого") }
    fun byte(): Int { need(1); return data[at++].toInt() and 0xFF }
    fun bool(): Boolean = byte() != 0
    fun int(): Int { need(4); var v = 0; repeat(4) { v = (v shl 8) or (data[at++].toInt() and 0xFF) }; return v }
    fun long(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or (data[at++].toLong() and 0xFF) }; return v }
    fun short(): Int { need(2); return ((data[at++].toInt() and 0xFF) shl 8) or (data[at++].toInt() and 0xFF) }
    fun string(): String { val n = int(); if (n < 0 || n > JdwpPeer.MAX_PACKET) throw IOException("строка JDWP длиной $n"); need(n); val s = String(data, at, n, Charsets.UTF_8); at += n; return s }
    fun objectId(): Long = id(sizes.objectId)
    fun typeId(): Long = id(sizes.typeId)
    fun methodId(): Long = id(sizes.methodId)
    fun fieldId(): Long = id(sizes.fieldId)
    fun frameId(): Long = id(sizes.frameId)
    private fun id(size: Int): Long { need(size); var v = 0L; repeat(size) { v = (v shl 8) or (data[at++].toLong() and 0xFF) }; return v }
    fun location(): JdwpLocation = JdwpLocation(byte(), typeId(), methodId(), long())
}

/** Размеры идентификаторов этой ВМ (`VirtualMachine.IDSizes`). */
internal data class JdwpSizes(val fieldId: Int = 8, val methodId: Int = 8, val objectId: Int = 8, val typeId: Int = 8, val frameId: Int = 8)

/** Место в коде: класс, метод, индекс байт-кода. */
data class JdwpLocation(val typeTag: Int, val classId: Long, val methodId: Long, val index: Long)
