package ca.saboor.discs.utils.extend

import java.lang.reflect.Method
import kotlinx.coroutines.CompletableDeferred
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.utils.extend.suspendSync

private val isFolia: Boolean by lazy {
    runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess
}

private val regionSchedulerExecuteMethod: Method? by lazy {
    if (!isFolia) null else runCatching {
        val server = Bukkit.getServer()
        val scheduler = server.javaClass.getMethod("getRegionScheduler").invoke(server)
        scheduler.javaClass.getMethod("execute", Plugin::class.java, Location::class.java, Runnable::class.java)
    }.getOrNull()
}

private val getRegionSchedulerMethod: Method? by lazy {
    if (!isFolia) null else runCatching {
        Bukkit.getServer().javaClass.getMethod("getRegionScheduler")
    }.getOrNull()
}

private val entityGetSchedulerMethod: Method? by lazy {
    if (!isFolia) null else runCatching {
        Entity::class.java.getMethod("getScheduler")
    }.getOrNull()
}

suspend fun <T> suspendSyncAt(
    location: Location,
    voiceServer: PlasmoVoiceServer?,
    plugin: JavaPlugin,
    task: () -> T
): T {
    if (voiceServer != null) {
        return voiceServer.minecraftServer.suspendSync(location, task)
    }

    val deferred = CompletableDeferred<T>()
    val r = Runnable {
        try {
            deferred.complete(task())
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
    }

    if (isFolia && getRegionSchedulerMethod != null && regionSchedulerExecuteMethod != null) {
        try {
            val server = Bukkit.getServer()
            val scheduler = getRegionSchedulerMethod!!.invoke(server)
            regionSchedulerExecuteMethod!!.invoke(scheduler, plugin, location, r)
            return deferred.await()
        } catch (_: Throwable) {}
    }

    Bukkit.getScheduler().runTask(plugin, r)
    return deferred.await()
}

suspend fun <T> suspendSyncFor(
    entity: Entity,
    voiceServer: PlasmoVoiceServer?,
    plugin: JavaPlugin,
    task: () -> T
): T {
    if (voiceServer != null) {
        return voiceServer.minecraftServer.suspendSync(entity, task)
    }

    val deferred = CompletableDeferred<T>()
    val r = Runnable {
        try {
            deferred.complete(task())
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
    }

    if (isFolia && entityGetSchedulerMethod != null) {
        try {
            val scheduler = entityGetSchedulerMethod!!.invoke(entity)
            val execMethod = scheduler.javaClass.methods.firstOrNull {
                it.name == "execute" && it.parameterCount == 4
            }
            if (execMethod != null) {
                execMethod.invoke(scheduler, plugin, r, null, 1L)
                return deferred.await()
            }
        } catch (_: Throwable) {}
    }

    Bukkit.getScheduler().runTask(plugin, r)
    return deferred.await()
}
