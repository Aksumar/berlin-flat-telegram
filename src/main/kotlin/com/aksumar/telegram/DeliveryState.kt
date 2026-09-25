package com.aksumar.telegram

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*

/** Single writer. Updates become visible in memory only after a durable atomic file replacement. */
class DeliveryState(private val path: Path) : AutoCloseable {
    private val mapper = jsonMapper()
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private var sent = linkedSetOf<String>()
    private var pending = linkedMapOf<String, List<String>>()

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        lockChannel = FileChannel.open(path.resolveSibling(path.fileName.toString() + ".lock"), CREATE, WRITE)
        try {
            lock = lockChannel.tryLock() ?: throw DeliveryException("Delivery state is already in use")
            if (Files.exists(path)) load()
        } catch (_: Exception) {
            lockChannel.close()
            throw DeliveryException("Cannot open delivery state; check file and single-writer ownership")
        }
    }

    private fun load() {
        val root = mapper.readTree(Files.readString(path))
        require(root.isObject && root["version"]?.isIntegralNumber == true && root["version"].asText() == "1")
        fun strings(node: com.fasterxml.jackson.databind.JsonNode): List<String> {
            require(node.isArray && node.all { it.isTextual })
            return node.map { it.asText() }
        }
        sent = strings(root.required("sent")).toCollection(linkedSetOf())
        root["pending"]?.let { node ->
            require(node.isObject)
            node.properties().forEach { (key, value) -> pending[key] = strings(value) }
        }
    }

    @Synchronized fun isSent(key: String) = key in sent
    @Synchronized fun deliveredChats(key: String): Set<String> = pending[key].orEmpty().toSet()

    @Synchronized fun recordChat(key: String, chat: String) {
        val next = LinkedHashMap(pending)
        next[key] = (next[key].orEmpty() + chat).distinct()
        persist(sent, next)
        pending = next
    }

    @Synchronized fun complete(key: String) {
        val nextSent = LinkedHashSet(sent).apply { add(key) }
        val nextPending = LinkedHashMap(pending).apply { remove(key) }
        persist(nextSent, nextPending)
        sent = nextSent
        pending = nextPending
    }

    private fun persist(newSent: Set<String>, newPending: Map<String, List<String>>) {
        val bytes = (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf("version" to 1, "sent" to newSent, "pending" to newPending)) + "\n").toByteArray(Charsets.UTF_8)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        FileChannel.open(path.toAbsolutePath().parent, READ).use { it.force(true) }
    }

    override fun close() { lock.release(); lockChannel.close() }
}
