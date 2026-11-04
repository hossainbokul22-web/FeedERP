package com.feederp.formulation

import org.junit.Assert.assertEquals
import org.junit.Test

class SimplexTest {
    @Test
    fun classicTwoVariableLp() {
        // min x+y ; x+2y>=4 ; 3x+y>=6  -> x=1.6, y=1.2, obj=2.8
        val s = Simplex.minimize(
            doubleArrayOf(1.0, 1.0),
            listOf(
                LpRow(doubleArrayOf(1.0, 2.0), LpRel.GE, 4.0),
                LpRow(doubleArrayOf(3.0, 1.0), LpRel.GE, 6.0),
            ),
        )
        assertEquals(LpStatus.OPTIMAL, s.status)
        assertEquals(1.6, s.x[0], 1e-6)
        assertEquals(1.2, s.x[1], 1e-6)
        assertEquals(2.8, s.objective, 1e-6)
    }

    @Test
    fun equalityConstraint() {
        // min 2x+3y ; x+y=10 ; x<=4  -> x=4,y=6 obj=26
        val s = Simplex.minimize(
            doubleArrayOf(2.0, 3.0),
            listOf(
                LpRow(doubleArrayOf(1.0, 1.0), LpRel.EQ, 10.0),
                LpRow(doubleArrayOf(1.0, 0.0), LpRel.LE, 4.0),
            ),
        )
        assertEquals(LpStatus.OPTIMAL, s.status)
        assertEquals(4.0, s.x[0], 1e-6)
        assertEquals(6.0, s.x[1], 1e-6)
        assertEquals(26.0, s.objective, 1e-6)
    }

    @Test
    fun infeasible() {
        val s = Simplex.minimize(
            doubleArrayOf(1.0),
            listOf(LpRow(doubleArrayOf(1.0), LpRel.LE, 1.0), LpRow(doubleArrayOf(1.0), LpRel.GE, 2.0)),
        )
        assertEquals(LpStatus.INFEASIBLE, s.status)
    }

    @Test
    fun unbounded() {
        // min -x ; x - y <= 1
        val s = Simplex.minimize(doubleArrayOf(-1.0, 0.0), listOf(LpRow(doubleArrayOf(1.0, -1.0), LpRel.LE, 1.0)))
        assertEquals(LpStatus.UNBOUNDED, s.status)
    }
}
