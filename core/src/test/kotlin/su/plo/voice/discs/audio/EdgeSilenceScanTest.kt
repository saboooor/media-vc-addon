package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EdgeSilenceScanTest {
    @Test fun `silent edges are removed while interior pauses remain on the timeline`() {
        val scan = EdgeSilenceScan(20000)
        for (time in 0L until 1000L step 20) scan.head(time, true)
        scan.head(1000, false)
        for (time in 5000L until 20000L step 20)
            scan.tail(time, time in 7000L..8000L || time >= 19000L)
        assertEquals(SongEdges(1000, 19000), scan.result())
    }
    @Test fun `incomplete scans never infer a silent ending`() {
        val scan = EdgeSilenceScan(20000)
        scan.head(0, false)
        for (time in 5000L until 10000L step 20) scan.tail(time, true)
        assertEquals(SongEdges(0, 20000), scan.result())
    }
    @Test fun `audible edges and fully silent songs retain safe original bounds`() {
        val scan = EdgeSilenceScan(1000)
        scan.head(0, false)
        for (time in 0L until 1000L step 20) scan.tail(time, false)
        assertEquals(SongEdges(0, 1000), scan.result())
        val silent = EdgeSilenceScan(1000)
        for (time in 0L until 1000L step 20) { silent.head(time, true); silent.tail(time, true) }
        assertEquals(SongEdges(0, 1000), silent.result())
    }
}
