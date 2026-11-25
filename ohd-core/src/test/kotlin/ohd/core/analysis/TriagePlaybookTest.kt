package ohd.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriagePlaybookTest {
    @Test
    fun stickyGraphZeroRoutesToNativeHeap() {
        val lines = listOf(
            "2026-11-04 09:14:58\twarn\tsticky_after_reclaim\treason=destroyRender after 90s: Total=496.9 (base=275.9 Δ=+221.1) Graph=63.5 (base=63.5 Δ=+0.0)",
            "2026-11-04 09:18:02\twarn\tnative_alloc_growth\theap_alloc=268.9MB (+88.5MB)",
        )
        val s = TriagePlaybook.summarizeAnomalies(lines)
        assertTrue(s.stickyGraphDeltaZero)
        assertTrue(s.stickyTotalGrowth)
        val plan = TriagePlaybook.plan(
            tags = s.counts,
            stickyGraphDeltaZero = s.stickyGraphDeltaZero,
            stickyTotalGrowth = s.stickyTotalGrowth,
            pixelMapLadder = false,
            xcomponentSticky = false,
            durationMin = 8.0,
            crit = 0,
            endRegistryAligned = true,
        )
        assertTrue(plan.tracks.contains(TriagePlaybook.Track.NATIVE_HEAP))
        assertTrue(plan.tracks.contains(TriagePlaybook.Track.SHORT_WINDOW).not() || plan.findings.any { it.track == TriagePlaybook.Track.SHORT_WINDOW })
        assertTrue(plan.findings.any { it.track == TriagePlaybook.Track.NATIVE_HEAP })
        assertTrue(plan.findings.any { it.track == TriagePlaybook.Track.SHORT_WINDOW })
    }

    @Test
    fun registryAlignedIsInfo() {
        val lines = listOf(
            "t\twarn\tregistry_skew\tnative remaining=2 > kotlin count=1",
            "t\twarn\tkeeping_egl_orphan\t2 renders",
        )
        val s = TriagePlaybook.summarizeAnomalies(lines)
        val plan = TriagePlaybook.plan(
            tags = s.counts,
            stickyGraphDeltaZero = false,
            stickyTotalGrowth = false,
            pixelMapLadder = false,
            xcomponentSticky = false,
            durationMin = 60.0,
            crit = 0,
            endRegistryAligned = true,
        )
        val reg = plan.findings.first { it.track == TriagePlaybook.Track.REGISTRY }
        assertEquals("info", reg.severity)
    }
}
