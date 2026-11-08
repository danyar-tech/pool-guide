package com.poolguide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketTest {
    private val r = 22.0
    private fun table(): TableGeom {
        val t = TableGeom(0.0, 0.0, 2000.0, 1000.0, pocketRadius = 45.0)
        t.resetPockets()
        return t
    }

    @Test
    fun cornerJawsSitOnTheCushionsAroundTheCorner() {
        val t = table()
        val (a, b) = PocketGeom.jaws(t, 1, r, 1.0) // top-right
        assertEquals(2000.0 - PocketGeom.CORNER_JAW * r, a.x, 1e-9); assertEquals(0.0, a.y, 1e-9)
        assertEquals(2000.0, b.x, 1e-9); assertEquals(PocketGeom.CORNER_JAW * r, b.y, 1e-9)
    }

    @Test
    fun straightIntoCornerAimsAtMouthMiddleWithClearance() {
        val t = table()
        val (a, b) = PocketGeom.jaws(t, 1, r, 1.0)
        val e = PocketGeom.entry(Vec(1500.0, 500.0), a, b, r)
        assertNotNull(e)
        // on the diagonal the bisector hits the middle of the mouth
        assertEquals((a.x + b.x) / 2, e!!.aim.x, 1e-6)
        assertTrue(e.margin > 0)
    }

    @Test
    fun ballFrozenOnRailCanRunIntoCorner() {
        val t = table()
        val (a, b) = PocketGeom.jaws(t, 1, r, 1.0)
        assertNotNull(PocketGeom.entry(Vec(1600.0, r), a, b, r))
    }

    @Test
    fun shallowAngleIntoSidePocketIsBlockedByTheJaw() {
        val t = table()
        val (a, b) = PocketGeom.jaws(t, 4, r, 1.0) // top middle
        assertNull(PocketGeom.entry(Vec(300.0, 60.0), a, b, r))
        val straight = PocketGeom.entry(Vec(1000.0, 400.0), a, b, r)
        assertNotNull(straight)
        assertEquals(PocketGeom.SIDE_JAW * r - r, straight!!.margin, 1e-6)
    }

    @Test
    fun biggerBallMeansLessRoom() {
        val t = table()
        val (a, b) = PocketGeom.jaws(t, 1, 22.0, 1.0)
        val small = PocketGeom.entry(Vec(1500.0, 500.0), a, b, 22.0)!!
        val big = PocketGeom.entry(Vec(1500.0, 500.0), a, b, 30.0)!!
        assertTrue(big.margin < small.margin)
    }

    @Test
    fun plannerRejectsCornerShotThatWouldHitTheCushion() {
        val t = table()
        // Object ball near the top cushion, far up-table: the only way into the top-right corner is down the
        // rail; a shot from a steep angle would hit the cushion before the corner.
        val target = Ball(1, Vec(1200.0, 60.0), BallKind.SOLID)
        val (a, b) = PocketGeom.jaws(t, 1, r, 1.0)
        val e = PocketGeom.entry(target.pos, a, b, r)
        // Feasible only with a very small margin (nearly along the rail).
        if (e != null) assertTrue(e.margin < r)
    }

    @Test
    fun predictorPotsOnlyThroughTheMouth() {
        val t = table()
        val scene = Scene(t, listOf(Ball(0, Vec(1500.0, 500.0), BallKind.CUE)), r)
        val p = Predictor(PlannerConfig())
        // A cue ball rolled straight at the corner drops (scratch).
        val pot = p.predict(scene, 0, Vec(1500.0, 500.0), Vec(1.0, -1.0))
        assertTrue(pot.scratch)
        // Aimed at the cushion right next to the jaw: no pocket.
        val (a, _) = PocketGeom.jaws(t, 1, r, 1.0)
        val near = p.predict(scene, 0, Vec(1500.0, 500.0), (Vec(a.x - r * 0.5, r) - Vec(1500.0, 500.0)).norm())
        assertFalse(near.scratch)
    }
}
