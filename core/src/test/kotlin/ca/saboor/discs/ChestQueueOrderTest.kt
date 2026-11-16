package ca.saboor.discs

import ca.saboor.discs.command.subcommand.ChestQueueOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChestQueueOrderTest {
    @Test fun `ordered queue ends unless loop is enabled`() {
        val order = ChestQueueOrder()
        assertEquals(0, order.next(3, -1, false, false))
        assertEquals(2, order.next(3, 1, false, false))
        assertNull(order.next(3, 2, false, false))
        assertEquals(0, order.next(3, 2, true, false))
        assertNull(order.next(0, 0, true, true))
    }

    @Test fun `shuffle plays every slot once and crossfade reserves the next song`() {
        repeat(50) {
            val order = ChestQueueOrder()
            var index = -1
            val played = mutableListOf<Int>()
            repeat(8) {
                val next = order.peek(8, index, false, true)
                assertEquals(next, order.peek(8, index, false, true))
                assertEquals(next, order.next(8, index, false, true))
                index = next!!
                played += index
            }
            assertEquals((0..7).toSet(), played.toSet())
            assertNull(order.next(8, index, false, true))
            val last = index
            val secondPass = mutableListOf<Int>()
            repeat(8) {
                index = order.next(8, index, true, true)!!
                secondPass += index
            }
            assertNotEquals(last, secondPass.first())
            assertEquals((0..7).toSet(), secondPass.toSet())
        }
    }

    @Test fun `disabling loop cancels a reserved wraparound`() {
        val order = ChestQueueOrder()
        assertEquals(0, order.next(1, -1, true, true))
        assertEquals(0, order.peek(1, 0, true, true))
        assertNull(order.next(1, 0, false, true))
    }

    @Test fun `single shuffled song can loop and removal can reset reservations`() {
        val order = ChestQueueOrder()
        assertEquals(0, order.next(1, -1, true, true))
        assertEquals(0, order.next(1, 0, true, true))
        order.peek(8, 0, false, true)
        order.reset()
        assertEquals(0, order.next(1, -1, false, true))
        assertEquals(1, order.next(3, -1, false, false, removedNext = 1))
    }
}
