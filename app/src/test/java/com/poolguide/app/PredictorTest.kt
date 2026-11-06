package com.poolguide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictorTest {
    private val r = 22.0
    private fun table(): TableGeom {
        val t = TableGeom(0.0, 0.0, 2000.0, 1000.0, pocketRadius = 45.0)
        t.resetPockets()
        return t
    }

    @Test
    fun straightLineIntoCornerPots() {
        val tb = table()
        val target = Ball(1, Vec(1500.0, 500.0), BallKind.SOLID)
        val dir = (tb.pockets[1].pos - target.pos).norm()
        val cue = Ball(0, target.pos - dir * 500.0, BallKind.CUE)
        val p = Predictor(PlannerConfig()).predict(Scene(tb, listOf(cue, target), r), 0, cue.pos, dir)
        assertEquals(1, p.hitBallId)
        assertEquals(1, p.objectPocket)
        assertEquals(0.0, p.cutDeg, 0.5)
        assertTrue(p.power > 0)
    }

    @Test
    fun cushionReflectsAngle() {
        val tb = table()
        val cue = Ball(0, Vec(300.0, 500.0), BallKind.CUE)
        // heading up-right at 45 degrees, nothing to hit: must bounce off the top cushion
        val p = Predictor(PlannerConfig()).predict(Scene(tb, listOf(cue), r), 0, cue.pos, Vec(1.0, -1.0))
        assertTrue(p.cuePath.size >= 3)
        val hit = p.cuePath[1]
        assertEquals(r, hit.y, 1e-6)
        val out = (p.cuePath[2] - hit).norm()
        assertEquals(1.0 / Math.sqrt(2.0), out.x, 1e-6)
        assertTrue(out.y > 0)
    }

    @Test
    fun cutShotGivesTangentPath() {
        val tb = table()
        val target = Ball(1, Vec(1000.0, 500.0), BallKind.SOLID)
        val cue = Ball(0, Vec(600.0, 520.0), BallKind.CUE)
        val p = Predictor(PlannerConfig()).predict(Scene(tb, listOf(cue, target), r), 0, cue.pos, Vec(1.0, 0.0))
        assertEquals(1, p.hitBallId)
        assertTrue(p.cutDeg > 10)
        assertTrue(p.cueAfterPath.isNotEmpty())
        // object and cue directions after contact are ~90 degrees apart (stun)
        val objDir = (p.objectPath[1] - p.objectPath[0]).norm()
        val cueDir = (p.cueAfterPath[1] - p.cueAfterPath[0]).norm()
        assertEquals(0.0, objDir.dot(cueDir), 1e-3)
    }
}
