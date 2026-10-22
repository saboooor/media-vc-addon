package ca.saboor.discs.screen

import java.util.UUID

/** The immutable viewer snapshot is read by PacketEvents' network threads. */
internal class ScreenMapViewers(mapIds: Collection<Int>) {
    private val maps = mapIds.toSet()
    @Volatile private var viewers: Set<UUID> = emptySet()

    fun update(viewerIds: Collection<UUID>) { viewers = viewerIds.toSet() }

    fun owns(viewerId: UUID, mapId: Int): Boolean = mapId in maps && viewerId in viewers
}
