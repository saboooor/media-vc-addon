package su.plo.voice.discs.utils.extend

import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.reflect.KProperty

interface Getter<out T> {
    val value: T
}

inline operator fun <T> Getter<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value

inline fun <reified T : Any> KoinComponent.getter(): Getter<T> =
    object : Getter<T> {
        override val value: T
            get() = get()
    }

inline fun <reified T : Any> KoinComponent.getterOrNull(): Getter<T?> =
    object : Getter<T?> {
        override val value: T?
            get() = getKoin().getOrNull()
    }