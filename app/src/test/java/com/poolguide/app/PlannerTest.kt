package com.poolguide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private fun table(): TableGeom {
        val t = TableGeom(0.0, 0.0, 2000.0, 1000.0, pocketRadius = 45.0)
        t.resetPockets()
        return t
    }

    private val r = 22.0

    @Test
    fun straightShotGhostBallIsBehindTarget() {
        val tb = table()
        // Target in line with the top-right corner pocket.
        val target = Ball(1, Vec(1500.0, 500.0), BallKind.SOLID)
        val dir = (tb.pockets[1].pos - target.pos).norm()
        val cue = Ball(0, target.pos - dir * 600.0, BallKind.CUE)
        val shots = Planner(PlannerConfig(allowBank = false, allowKick = false))
            .plan(Scene(tb, listOf(cue, target), r))
        val best = shots.first()
        assertEquals(1, best.pocketIndex)
        assertEquals(0.0, best.cutDeg, 0.5)
        val expectedGhost = target.pos - dir * (2 * r)
        assertEquals(expectedGhost.x, best.ghost.x, 1e-6)
        assertEquals(expectedGhost.y, best.ghost.y, 1e-6)
        assertTrue(best.power > 0.0 && best.power < 1.0)
    }

    @Test
    fun blockedPathIsRejected() {
        val tb = table()
        val target = Ball(1, Vec(1000.0, 300.0), BallKind.SOLID)
        val cue = Ball(0, Vec(1000.0, 800.0), BallKind.CUE)
        // Blocker sits directly between cue and target; the straight-up side pocket shot must not appear.
        val blocker = Ball(2, Vec(1000.0, 550.0), BallKind.STRIPE)
        val shots = Planner(PlannerConfig(group = Group.SOLIDS, allowBank = false, allowKick = false))
            .plan(Scene(tb, listOf(cue, target, blocker), r))
        assertFalse(shots.any { it.targetId == 1 && it.pocketIndex == 4 && it.type == ShotType.DIRECT })
    }

    @Test
    fun kickFindsWayAroundBlocker() {
        val tb = table()
        val target = Ball(1, Vec(1000.0, 300.0), BallKind.SOLID)
        val cue = Ball(0, Vec(1000.0, 800.0), BallKind.CUE)
        val blocker = Ball(2, Vec(1000.0, 550.0), BallKind.STRIPE)
        val shots = Planner(PlannerConfig(group = Group.SOLIDS, allowBank = false, allowKick = true))
            .plan(Scene(tb, listOf(cue, target, blocker), r))
        assertTrue(shots.isNotEmpty())
    }

    @Test
    fun longerShotNeedsMorePower() {
        val tb = table()
        val target = Ball(1, Vec(1500.0, 500.0), BallKind.SOLID)
        val dir = (tb.pockets[1].pos - target.pos).norm()
        val p = Planner(PlannerConfig(allowBank = false, allowKick = false))
        val near = p.plan(Scene(tb, listOf(Ball(0, target.pos - dir * 200.0, BallKind.CUE), target), r)).first()
        val far = p.plan(Scene(tb, listOf(Ball(0, target.pos - dir * 900.0, BallKind.CUE), target), r)).first()
        assertTrue(far.power > near.power)
        assertTrue(near.score > far.score)
    }

    @Test
    fun eightTargetedWhenGroupCleared() {
        val tb = table()
        val eight = Ball(8, Vec(1000.0, 500.0), BallKind.EIGHT)
        val stripe = Ball(2, Vec(400.0, 300.0), BallKind.STRIPE)
        val cue = Ball(0, Vec(1500.0, 700.0), BallKind.CUE)
        val targets = Planner(PlannerConfig(group = Group.SOLIDS)).pickTargets(Scene(tb, listOf(cue, eight, stripe), r))
        assertEquals(listOf(8), targets.map { it.id })
    }

    @Test
    fun tableEncodeRoundTrip() {
        val tb = table()
        val back = TableGeom.decode(tb.encode())
        assertNotNull(back)
        assertEquals(6, back!!.pockets.size)
        assertEquals(tb.right, back.right, 1e-9)
    }
}
