package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/** Сбой процесса в модели: не `IOException` — хранилище его не перехватывает, как не перехватит смерть процесса. */
class SimulatedCrash : RuntimeException("сбой (модель)")

/**
 * Файловая система с моделью сбоев (D-24; ТЗ, §95, §103) — строже обычной:
 *
 * - данные файла переживают [crash] только после `fsync`; недописанный
 *   хвост дописывания сохраняется случайной частью, перезаписанный без
 *   fsync файл становится прежним, пустым или началом нового;
 * - создание, переименование и удаление записи каталога переживают сбой
 *   только после `fsyncDir` этого каталога;
 * - [crashAtOp] обрывает процесс перед изменяющей операцией с этим
 *   номером; [failAtOp] — отказ ввода-вывода (диск полон): запись успевает
 *   положить случайное начало данных.
 */
class SimulatedVfs(private val random: RandomSource = RandomSource.seeded(1)) : Vfs {
    private class Node(var content: ByteArray, var durable: ByteArray)

    private val ns = HashMap<String, Node>()
    private val durableNs = HashMap<String, Node>()

    var ops: Int = 0
        private set
    var crashAtOp: Int? = null
    var failAtOp: Int? = null

    private fun step(): Boolean {
        val n = ops++
        if (crashAtOp == n) throw SimulatedCrash()
        return failAtOp == n
    }

    override fun read(path: String): ByteArray? = ns[path]?.content?.copyOf()

    override fun exists(path: String): Boolean = path in ns

    override fun list(dir: String): List<String> = ns.keys.filter { Vfs.parentOf(it) == dir }.map { it.substringAfterLast('/') }.sorted()

    override fun write(path: String, bytes: ByteArray) {
        val fail = step()
        val data = if (fail) bytes.copyOf(random.nextInt(bytes.size + 1)) else bytes.copyOf()
        val node = ns[path]
        if (node == null) ns[path] = Node(data, ByteArray(0)) else node.content = data
        if (fail) throw IOException("диск полон (модель)")
    }

    override fun append(path: String, bytes: ByteArray) {
        val fail = step()
        val data = if (fail) bytes.copyOf(random.nextInt(bytes.size + 1)) else bytes
        val node = ns.getOrPut(path) { Node(ByteArray(0), ByteArray(0)) }
        node.content = node.content + data
        if (fail) throw IOException("диск полон (модель)")
    }

    override fun truncate(path: String, size: Long) {
        if (step()) throw IOException("отказ (модель)")
        val node = ns[path] ?: throw IOException("нет файла")
        node.content = node.content.copyOf(size.toInt())
    }

    override fun fsync(path: String) {
        if (step()) throw IOException("отказ (модель)")
        val node = ns[path] ?: throw IOException("нет файла")
        node.durable = node.content.copyOf()
    }

    override fun rename(from: String, to: String) {
        if (step()) throw IOException("отказ (модель)")
        val node = ns.remove(from) ?: throw IOException("нет файла")
        ns[to] = node
    }

    override fun delete(path: String) {
        if (step()) throw IOException("отказ (модель)")
        ns.remove(path)
    }

    override fun fsyncDir(dir: String) {
        if (step()) throw IOException("отказ (модель)")
        for (k in (ns.keys + durableNs.keys).filter { Vfs.parentOf(it) == dir }) {
            val node = ns[k]
            if (node == null) durableNs.remove(k) else durableNs[k] = node
        }
    }

    override fun mkdirs(dir: String) = Unit

    /** Сбой: остаётся только то, что было зафиксировано. */
    fun crash() {
        ns.clear()
        ns.putAll(durableNs)
        for (node in ns.values.toSet()) {
            val c = node.content
            val d = node.durable
            node.content = when {
                c.contentEquals(d) -> d
                c.size > d.size && c.copyOf(d.size).contentEquals(d) -> c.copyOf(d.size + random.nextInt(c.size - d.size + 1))
                else -> when (random.nextInt(3)) {
                    0 -> d
                    1 -> ByteArray(0)
                    else -> c.copyOf(random.nextInt(c.size + 1))
                }
            }
            node.durable = node.content.copyOf()
        }
        crashAtOp = null
        failAtOp = null
    }

    /** Порча байта на «диске» — для тестов обнаружения повреждений. */
    fun corrupt(path: String, offset: Int) {
        val node = ns.getValue(path)
        node.content[offset] = (node.content[offset].toInt() xor 0x5a).toByte()
        node.durable = node.content.copyOf()
    }

    fun files(): Set<String> = ns.keys.toSet()
}

/**
 * Сценарий, оборванный перед каждой изменяющей операцией по очереди:
 * для k = 0…N — новое хранилище, [setup], сбой на операции k внутри
 * [action], восстановление, [check]. N — число операций [action] без сбоя.
 */
fun crashEverywhere(setup: (SimulatedVfs) -> Unit, action: (SimulatedVfs) -> Unit, check: (SimulatedVfs, Int) -> Unit) {
    val probe = SimulatedVfs()
    setup(probe)
    val before = probe.ops
    action(probe)
    val total = probe.ops - before
    for (k in 0..total) {
        val vfs = SimulatedVfs(RandomSource.seeded(1000L + k))
        setup(vfs)
        vfs.crashAtOp = vfs.ops + k
        try {
            action(vfs)
        } catch (_: SimulatedCrash) {
            // процесс «умер» на операции k
        }
        vfs.crash()
        check(vfs, k)
    }
}
