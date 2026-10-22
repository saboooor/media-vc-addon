package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanionProtocolTest {
    @Test fun `only the current companion protocol negotiates RGB video`() {
        assertTrue(CompanionScreens.supportsProtocol(byteArrayOf(3)))
        assertFalse(CompanionScreens.supportsProtocol(byteArrayOf(2)))
        assertFalse(CompanionScreens.supportsProtocol(byteArrayOf(1)))
        assertFalse(CompanionScreens.supportsProtocol(byteArrayOf()))
        assertFalse(CompanionScreens.supportsProtocol(byteArrayOf(3, 0)))
    }
}
