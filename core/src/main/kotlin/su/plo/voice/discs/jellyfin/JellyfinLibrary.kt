package su.plo.voice.discs.jellyfin

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Server-private token store. A disc grants playback of one selected item, not library access. */
class JellyfinLibrary(private val file: Path) {
    class Connection(val id: UUID, val owner: UUID, val session: JellyfinClient.Session) {
        override fun toString() = "Jellyfin connection $id"
    }
    data class Grant(val id: UUID, val connection: UUID, val mediaId: String, val label: String, val type: String? = "Audio", val durationMs: Long = 0)
    private class State(val connections: List<Connection> = emptyList(), val grants: List<Grant> = emptyList())
    private val connections = mutableMapOf<UUID, Connection>()
    private val grants = mutableMapOf<UUID, Grant>()
    init {
        if (Files.exists(file)) {
            val state = Gson().fromJson(Files.readString(file), State::class.java)
            state.connections.forEach { connection ->
                require(JellyfinClient.normalizeUrl(connection.session.baseUrl) == connection.session.baseUrl)
                JellyfinClient.mediaId(connection.session.userId)
                JellyfinClient.authorization(connection.session.deviceId, connection.session.token)
                connections[connection.owner] = connection
            }
            state.grants.forEach { JellyfinClient.mediaId(it.mediaId); grants[it.id] = it }
        }
    }
    @Synchronized fun connection(owner: UUID): Connection? = connections[owner]
    @Synchronized fun connect(owner: UUID, session: JellyfinClient.Session): Connection {
        val old = connections[owner]
        val connection = Connection(UUID.nameUUIDFromBytes("$owner|${session.baseUrl}|${session.userId}".toByteArray(Charsets.UTF_8)), owner, session)
        connections[owner] = connection
        try { save() } catch (e: Exception) {
            if (old == null) connections.remove(owner) else connections[owner] = old
            throw e
        }
        return connection
    }
    @Synchronized fun disconnect(owner: UUID): Connection? {
        val old = connections.remove(owner) ?: return null
        try { save() } catch (e: Exception) { connections[owner] = old; throw e }
        return old
    }
    @Synchronized fun grant(connection: Connection, media: JellyfinClient.Media): String {
        check(connections[connection.owner] === connection) { "Jellyfin connection changed. Open the menu again." }
        val grant = Grant(UUID.randomUUID(), connection.id, media.id, media.label, media.type, media.durationMs)
        grants[grant.id] = grant
        try { save() } catch (e: Exception) { grants.remove(grant.id); throw e }
        return "jellyfin:${grant.id}"
    }
    @Synchronized fun revoke(identifier: String) {
        val id = parseReference(identifier) ?: return
        grants.remove(id)
        save()
    }
    @Synchronized fun resolve(identifier: String): Pair<Connection, Grant> {
        val grant = parseReference(identifier)?.let { grants[it] }
            ?: throw JellyfinException("This Jellyfin disc is not registered on this server.")
        val connection = connections.values.firstOrNull { it.id == grant.connection }
            ?: throw JellyfinException("The owner of this disc needs to reconnect Jellyfin.")
        return connection to grant
    }
    private fun save() {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "jellyfin-", ".tmp")
        try {
            try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
            catch (_: UnsupportedOperationException) {}
            Files.writeString(temp, Gson().toJson(State(connections.values.toList(), grants.values.toList())))
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    companion object {
        fun parseReference(value: String): UUID? = if (!value.startsWith("jellyfin:")) null
            else runCatching { UUID.fromString(value.removePrefix("jellyfin:")) }.getOrNull()
    }
}
