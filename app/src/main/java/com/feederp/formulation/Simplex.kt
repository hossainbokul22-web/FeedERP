package com.feederp.formulation

import kotlin.math.abs

enum class LpRel { LE, GE, EQ }

/** একটি সীমা: coef · x (rel) rhs। সব ভ্যারিয়েবল x >= 0। */
class LpRow(val coef: DoubleArray, val rel: LpRel, val rhs: Double)

enum class LpStatus { OPTIMAL, INFEASIBLE, UNBOUNDED }

class LpSolution(val status: LpStatus, val x: DoubleArray, val objective: Double)

/**
 * বিশুদ্ধ Kotlin দ্বি-ধাপ সিমপ্লেক্স (ডেন্স ট্যাবলো, Bland এর নিয়ম, তাই চক্র হয় না)।
 * minimize c·x, subject to rows, x >= 0। ছোট সমস্যার (কয়েক ডজন ভ্যারিয়েবল) জন্য।
 * Android/Room নির্ভরতা নেই।
 */
object Simplex {
    private const val EPS = 1e-9
    private const val FEAS_TOL = 1e-7
    private const val MAX_ITER = 20000

    fun minimize(c: DoubleArray, rows: List<LpRow>): LpSolution {
        val n = c.size
        val m = rows.size

        // সারি স্কেল করা (সবচেয়ে বড় সহগ = 1) ও rhs >= 0 বানানো
        val coefs = ArrayList<DoubleArray>(m)
        val rels = ArrayList<LpRel>(m)
        val rhss = DoubleArray(m)
        for ((i, r) in rows.withIndex()) {
            require(r.coef.size == n) { "সারির সহগের সংখ্যা মেলেনি" }
            var scale = 0.0
            for (v in r.coef) scale = maxOf(scale, abs(v))
            if (scale < EPS) scale = 1.0
            var sign = 1.0 / scale
            var rel = r.rel
            if (r.rhs < 0) {
                sign = -sign
                rel = when (rel) { LpRel.LE -> LpRel.GE; LpRel.GE -> LpRel.LE; LpRel.EQ -> LpRel.EQ }
            }
            coefs += DoubleArray(n) { r.coef[it] * sign }
            rels += rel
            rhss[i] = r.rhs * sign
        }

        val slackCount = rels.count { it != LpRel.EQ }
        val artCount = rels.count { it != LpRel.LE }
        val cols = n + slackCount + artCount
        val artStart = n + slackCount
        val t = Array(m + 1) { DoubleArray(cols + 1) }
        val basis = IntArray(m)
        var si = n
        var ai = artStart
        for (i in 0 until m) {
            for (j in 0 until n) t[i][j] = coefs[i][j]
            t[i][cols] = rhss[i]
            when (rels[i]) {
                LpRel.LE -> { t[i][si] = 1.0; basis[i] = si; si++ }
                LpRel.GE -> { t[i][si] = -1.0; si++; t[i][ai] = 1.0; basis[i] = ai; ai++ }
                LpRel.EQ -> { t[i][ai] = 1.0; basis[i] = ai; ai++ }
            }
        }

        // ধাপ ১: কৃত্রিম ভ্যারিয়েবলের যোগফল ন্যূনতম
        if (artCount > 0) {
            val cost1 = DoubleArray(cols) { if (it >= artStart) 1.0 else 0.0 }
            val st = run(t, basis, cost1, cols, m, cols)
            if (st == LpStatus.UNBOUNDED) return LpSolution(LpStatus.INFEASIBLE, DoubleArray(n), 0.0)
            if (-t[m][cols] > FEAS_TOL) return LpSolution(LpStatus.INFEASIBLE, DoubleArray(n), 0.0)
            // বেসিসে থাকা কৃত্রিম ভ্যারিয়েবল (মান ০) বের করা
            for (i in 0 until m) {
                if (basis[i] >= artStart) {
                    var pc = -1
                    for (j in 0 until artStart) if (abs(t[i][j]) > 1e-7) { pc = j; break }
                    if (pc >= 0) pivot(t, basis, i, pc, m, cols)
                }
            }
        }

        // ধাপ ২: আসল উদ্দেশ্য
        val cost2 = DoubleArray(cols) { if (it < n) c[it] else 0.0 }
        val st2 = run(t, basis, cost2, artStart, m, cols)
        if (st2 == LpStatus.UNBOUNDED) return LpSolution(LpStatus.UNBOUNDED, DoubleArray(n), 0.0)
        val x = DoubleArray(n)
        for (i in 0 until m) if (basis[i] < n) x[basis[i]] = t[i][cols].coerceAtLeast(0.0)
        var obj = 0.0
        for (j in 0 until n) obj += c[j] * x[j]
        return LpSolution(LpStatus.OPTIMAL, x, obj)
    }

    /** [allowedCols] এর নিচের কলামগুলোই এন্টার করতে পারে। ফেরত: OPTIMAL বা UNBOUNDED। */
    private fun run(
        t: Array<DoubleArray>, basis: IntArray, cost: DoubleArray, allowedCols: Int, m: Int, cols: Int,
    ): LpStatus {
        val z = t[m]
        for (j in 0..cols) z[j] = if (j < cols) cost[j] else 0.0
        for (i in 0 until m) {
            val cb = cost[basis[i]]
            if (cb != 0.0) for (j in 0..cols) z[j] -= cb * t[i][j]
        }
        var iter = 0
        while (iter++ < MAX_ITER) {
            var enter = -1
            for (j in 0 until allowedCols) if (z[j] < -EPS) { enter = j; break }
            if (enter < 0) return LpStatus.OPTIMAL
            var leave = -1
            var best = Double.MAX_VALUE
            for (i in 0 until m) {
                val a = t[i][enter]
                if (a > EPS) {
                    val ratio = t[i][cols] / a
                    if (ratio < best - 1e-12 || (abs(ratio - best) <= 1e-12 && leave >= 0 && basis[i] < basis[leave])) {
                        best = ratio; leave = i
                    }
                }
            }
            if (leave < 0) return LpStatus.UNBOUNDED
            pivot(t, basis, leave, enter, m, cols)
        }
        return LpStatus.OPTIMAL // অতি-বিরল: সর্বোচ্চ ধাপ পেরোলে বর্তমান সমাধান
    }

    private fun pivot(t: Array<DoubleArray>, basis: IntArray, row: Int, col: Int, m: Int, cols: Int) {
        val p = t[row][col]
        for (j in 0..cols) t[row][j] /= p
        for (i in 0..m) {
            if (i == row) continue
            val f = t[i][col]
            if (f != 0.0) for (j in 0..cols) t[i][j] -= f * t[row][j]
        }
        basis[row] = col
    }
}
