package ca.saboor.discs.jellyfin

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Server-private token store. A disc grants playback of one selected item, not library access. */
class JellyfinLibrary(private val file: Path,
    private val persist: (Path, String) -> Unit = ::writePrivateFile) {
    class Connection(val id: UUID, val owner: UUID, val session: JellyfinClient.Session) {
        override fun toString() = "Jellyfin connection $id"
    }
    data class Grant(val id: UUID, val connection: UUID, val mediaId: String, val label: String, val type: String? = "Audio", val durationMs: Long = 0)
    private class State(val connections: List<Connection> = emptyList(), val grants: List<Grant> = emptyList())
    private class Snapshot(val connections: Map<UUID, Connection>, val grants: Map<UUID, Grant>) {
        val byId = connections.values.associateBy { it.id }
    }
    private val gson = Gson()
    @Volatile private var snapshot: Snapshot
    init {
        val state = if (Files.exists(file)) gson.fromJson(Files.readString(file), State::class.java) else State()
        state.connections.forEach { connection ->
            require(JellyfinClient.normalizeUrl(connection.session.baseUrl) == connection.session.baseUrl)
            JellyfinClient.mediaId(connection.session.userId)
            JellyfinClient.authorization(connection.session.deviceId, connection.session.token)
        }
        state.grants.forEach { JellyfinClient.mediaId(it.mediaId) }
        snapshot = Snapshot(state.connections.associateBy { it.owner }, state.grants.associateBy { it.id })
    }
    fun connection(owner: UUID): Connection? = snapshot.connections[owner]

    // Writers serialize with each other; readers keep using the last durable immutable snapshot.
    @Synchronized fun connect(owner: UUID, session: JellyfinClient.Session): Connection {
        val connection = Connection(UUID.nameUUIDFromBytes("$owner|${session.baseUrl}|${session.userId}".toByteArray(Charsets.UTF_8)), owner, session)
        commit(Snapshot(snapshot.connections + (owner to connection), snapshot.grants))
        return connection
    }
    @Synchronized fun disconnect(owner: UUID): Connection? {
        val old = snapshot.connections[owner] ?: return null
        commit(Snapshot(snapshot.connections - owner, snapshot.grants))
        return old
    }
    @Synchronized fun grant(connection: Connection, media: JellyfinClient.Media): String {
        check(snapshot.connections[connection.owner] === connection) { "Jellyfin connection changed. Open the menu again." }
        val grant = Grant(UUID.randomUUID(), connection.id, media.id, media.label, media.type, media.durationMs)
        commit(Snapshot(snapshot.connections, snapshot.grants + (grant.id to grant)))
        return "jellyfin:${grant.id}"
    }
    @Synchronized fun revoke(identifier: String) {
        val id = parseReference(identifier) ?: return
        if (id !in snapshot.grants) return
        commit(Snapshot(snapshot.connections, snapshot.grants - id))
    }
    fun resolve(identifier: String): Pair<Connection, Grant> {
        val current = snapshot
        val grant = parseReference(identifier)?.let { current.grants[it] }
            ?: throw JellyfinException("This Jellyfin disc is not registered on this server.")
        val connection = current.byId[grant.connection]
            ?: throw JellyfinException("The owner of this disc needs to reconnect Jellyfin.")
        return connection to grant
    }
    private fun commit(next: Snapshot) {
        persist(file, gson.toJson(State(next.connections.values.toList(), next.grants.values.toList())))
        snapshot = next
    }
    companion object {
        private fun writePrivateFile(file: Path, json: String) {
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, "jellyfin-", ".tmp")
            try {
                try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                catch (_: UnsupportedOperationException) {}
                Files.writeString(temp, json)
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temp) }
        }
        fun parseReference(value: String): UUID? = if (!value.startsWith("jellyfin:")) null
            else runCatching { UUID.fromString(value.removePrefix("jellyfin:")) }.getOrNull()
    }
}
