package com.chessassist

import kotlin.math.abs

const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

// Board: index = rank * 8 + file, rank 0 = white's first rank.
// Pieces: +1..+6 white, -1..-6 black, 0 empty. Move: from | to << 6 | promo << 12.
fun moveFrom(m: Int) = m and 63
fun moveTo(m: Int) = (m shr 6) and 63
fun movePromo(m: Int) = (m shr 12) and 15
fun sqName(i: Int) = "${'a' + i % 8}${i / 8 + 1}"
fun moveText(m: Int): String {
    val promo = movePromo(m)
    val suffix = if (promo != 0) "=" + "?PNBRQK"[promo] else ""
    return sqName(moveFrom(m)) + " → " + sqName(moveTo(m)) + suffix
}

class MoveList {
    val a = IntArray(320)
    var n = 0
    fun add(m: Int) {
        a[n++] = m
    }
}

class Position(val b: IntArray, val side: Int, val castling: Int, val ep: Int)

object Rules {
    private val KN = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(-1, 2), intArrayOf(-2, 1),
        intArrayOf(1, -2), intArrayOf(2, -1), intArrayOf(-1, -2), intArrayOf(-2, -1)
    )
    private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
    private val ORTH = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    fun initial(): Position {
        val b = IntArray(64)
        val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        for (f in 0..7) {
            b[f] = back[f]
            b[8 + f] = PAWN
            b[48 + f] = -PAWN
            b[56 + f] = -back[f]
        }
        return Position(b, 1, 15, -1)
    }

    fun attacked(b: IntArray, sq: Int, by: Int): Boolean {
        val r = sq / 8
        val f = sq % 8
        val pr = r - by
        if (pr in 0..7) {
            for (k in 0..1) {
                val pf = f + 2 * k - 1
                if (pf in 0..7 && b[pr * 8 + pf] == by * PAWN) return true
            }
        }
        for (d in KN) {
            val rr = r + d[0]
            val ff = f + d[1]
            if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] == by * KNIGHT) return true
        }
        for (dr in -1..1) {
            for (df in -1..1) {
                if (dr == 0 && df == 0) continue
                val rr = r + dr
                val ff = f + df
                if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] == by * KING) return true
            }
        }
        for (d in DIAG) {
            var rr = r + d[0]
            var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val p = b[rr * 8 + ff]
                if (p != 0) {
                    if (p == by * BISHOP || p == by * QUEEN) return true
                    break
                }
                rr += d[0]
                ff += d[1]
            }
        }
        for (d in ORTH) {
            var rr = r + d[0]
            var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val p = b[rr * 8 + ff]
                if (p != 0) {
                    if (p == by * ROOK || p == by * QUEEN) return true
                    break
                }
                rr += d[0]
                ff += d[1]
            }
        }
        return false
    }

    private fun kingSquare(b: IntArray, side: Int): Int {
        for (i in 0..63) if (b[i] == side * KING) return i
        return -1
    }

    fun inCheck(pos: Position): Boolean {
        val k = kingSquare(pos.b, pos.side)
        return k >= 0 && attacked(pos.b, k, -pos.side)
    }

    private fun addPawn(out: MoveList, from: Int, to: Int, promote: Boolean) {
        if (promote) {
            out.add(from or (to shl 6) or (QUEEN shl 12))
            out.add(from or (to shl 6) or (ROOK shl 12))
            out.add(from or (to shl 6) or (BISHOP shl 12))
            out.add(from or (to shl 6) or (KNIGHT shl 12))
        } else {
            out.add(from or (to shl 6))
        }
    }

    private fun slide(b: IntArray, sq: Int, dirs: Array<IntArray>, side: Int, out: MoveList) {
        val r = sq / 8
        val f = sq % 8
        for (d in dirs) {
            var rr = r + d[0]
            var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val t = rr * 8 + ff
                val q = b[t]
                if (q * side > 0) break
                out.add(sq or (t shl 6))
                if (q != 0) break
                rr += d[0]
                ff += d[1]
            }
        }
    }

    fun pseudo(pos: Position, out: MoveList) {
        val b = pos.b
        val side = pos.side
        for (sq in 0..63) {
            val p = b[sq]
            if (p * side <= 0) continue
            val r = sq / 8
            val f = sq % 8
            when (abs(p)) {
                PAWN -> {
                    val nr = r + side
                    if (nr in 0..7) {
                        val last = if (side == 1) 7 else 0
                        val start = if (side == 1) 1 else 6
                        val t = nr * 8 + f
                        if (b[t] == 0) {
                            addPawn(out, sq, t, nr == last)
                            val t2 = (r + 2 * side) * 8 + f
                            if (r == start && b[t2] == 0) out.add(sq or (t2 shl 6))
                        }
                        for (k in 0..1) {
                            val nf = f + 2 * k - 1
                            if (nf !in 0..7) continue
                            val tc = nr * 8 + nf
                            if (b[tc] * side < 0) {
                                addPawn(out, sq, tc, nr == last)
                            } else if (tc == pos.ep && b[tc] == 0) {
                                out.add(sq or (tc shl 6))
                            }
                        }
                    }
                }
                KNIGHT -> {
                    for (d in KN) {
                        val rr = r + d[0]
                        val ff = f + d[1]
                        if (rr !in 0..7 || ff !in 0..7) continue
                        val t = rr * 8 + ff
                        if (b[t] * side <= 0) out.add(sq or (t shl 6))
                    }
                }
                BISHOP -> slide(b, sq, DIAG, side, out)
                ROOK -> slide(b, sq, ORTH, side, out)
                QUEEN -> {
                    slide(b, sq, DIAG, side, out)
                    slide(b, sq, ORTH, side, out)
                }
                KING -> {
                    for (dr in -1..1) {
                        for (df in -1..1) {
                            if (dr == 0 && df == 0) continue
                            val rr = r + dr
                            val ff = f + df
                            if (rr !in 0..7 || ff !in 0..7) continue
                            val t = rr * 8 + ff
                            if (b[t] * side <= 0) out.add(sq or (t shl 6))
                        }
                    }
                    val base = if (side == 1) 0 else 56
                    if (sq == base + 4) {
                        val ksBit = if (side == 1) 1 else 4
                        val qsBit = if (side == 1) 2 else 8
                        val enemy = -side
                        if ((pos.castling and ksBit) != 0 &&
                            b[base + 5] == 0 && b[base + 6] == 0 && b[base + 7] == side * ROOK &&
                            !attacked(b, base + 4, enemy) && !attacked(b, base + 5, enemy) && !attacked(b, base + 6, enemy)
                        ) out.add(sq or ((base + 6) shl 6))
                        if ((pos.castling and qsBit) != 0 &&
                            b[base + 1] == 0 && b[base + 2] == 0 && b[base + 3] == 0 && b[base] == side * ROOK &&
                            !attacked(b, base + 4, enemy) && !attacked(b, base + 3, enemy) && !attacked(b, base + 2, enemy)
                        ) out.add(sq or ((base + 2) shl 6))
                    }
                }
            }
        }
    }

    private fun crMask(sq: Int): Int = when (sq) {
        0 -> 2.inv()
        7 -> 1.inv()
        56 -> 8.inv()
        63 -> 4.inv()
        4 -> 3.inv()
        60 -> 12.inv()
        else -> -1
    }

    fun make(pos: Position, m: Int): Position {
        val b = pos.b.copyOf()
        val from = moveFrom(m)
        val to = moveTo(m)
        val promo = movePromo(m)
        val p = b[from]
        val side = pos.side
        val type = abs(p)
        var ep = -1
        if (type == PAWN && to == pos.ep && b[to] == 0 && from % 8 != to % 8) b[to - 8 * side] = 0
        if (type == KING && abs(to - from) == 2) {
            if (to > from) {
                b[from + 1] = b[from + 3]
                b[from + 3] = 0
            } else {
                b[from - 1] = b[from - 4]
                b[from - 4] = 0
            }
        }
        if (type == PAWN && abs(to - from) == 16) ep = (from + to) / 2
        b[to] = if (promo != 0) side * promo else p
        b[from] = 0
        val castling = pos.castling and crMask(from) and crMask(to)
        return Position(b, -side, castling, ep)
    }

    fun legal(pos: Position): MoveList {
        val tmp = MoveList()
        pseudo(pos, tmp)
        val out = MoveList()
        for (i in 0 until tmp.n) {
            val m = tmp.a[i]
            val q = make(pos, m)
            val k = kingSquare(q.b, pos.side)
            if (k >= 0 && !attacked(q.b, k, -pos.side)) out.add(m)
        }
        return out
    }

    // Finds the one or two plies that turn `pos` into the observed piece placement.
    fun match(pos: Position, obs: IntArray): List<Int>? {
        val l1 = legal(pos)
        for (i in 0 until l1.n) {
            val q = make(pos, l1.a[i])
            if (q.b.contentEquals(obs)) return listOf(l1.a[i])
        }
        for (i in 0 until l1.n) {
            val q = make(pos, l1.a[i])
            val l2 = legal(q)
            for (j in 0 until l2.n) {
                val r = make(q, l2.a[j])
                if (r.b.contentEquals(obs)) return listOf(l1.a[i], l2.a[j])
            }
        }
        return null
    }
}

class Engine {
    @Volatile
    private var stop = false
    private var deadline = 0L
    private var nodes = 0L

    fun abort() {
        stop = true
    }

    fun best(pos: Position, millis: Long, maxDepth: Int = 7): Int? {
        val root = Rules.legal(pos)
        if (root.n == 0) return null
        var best = root.a[0]
        stop = false
        nodes = 0
        deadline = System.currentTimeMillis() + millis
        for (d in 1..maxDepth) {
            val ms = order(pos, root, best)
            var alpha = -INF
            var bestThis = -1
            var aborted = false
            for (m in ms) {
                val s = -negamax(Rules.make(pos, m), d - 1, -INF, -alpha, 1)
                if (stop) {
                    aborted = true
                    break
                }
                if (bestThis == -1 || s > alpha) {
                    alpha = s
                    bestThis = m
                }
            }
            if (aborted || bestThis == -1) break
            best = bestThis
            if (alpha > MATE - 100) break
            if (System.currentTimeMillis() > deadline) break
        }
        return best
    }

    private fun timeCheck() {
        if ((++nodes and 1023L) == 0L && System.currentTimeMillis() > deadline) stop = true
    }

    private fun negamax(pos: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        timeCheck()
        if (stop) return 0
        val inChk = Rules.inCheck(pos)
        val d = if (inChk && ply < 10) depth + 1 else depth
        if (d <= 0) return quiesce(pos, alphaIn, beta, ply)
        val ml = Rules.legal(pos)
        if (ml.n == 0) return if (inChk) -MATE + ply else 0
        var alpha = alphaIn
        var best = -INF
        for (m in order(pos, ml, 0)) {
            val s = -negamax(Rules.make(pos, m), d - 1, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (s > best) best = s
            if (s > alpha) alpha = s
            if (alpha >= beta) break
        }
        return best
    }

    private fun quiesce(pos: Position, alphaIn: Int, beta: Int, ply: Int): Int {
        timeCheck()
        if (stop) return 0
        val stand = evaluate(pos)
        if (ply > 24 || stand >= beta) return stand
        var alpha = alphaIn
        if (stand > alpha) alpha = stand
        val ml = Rules.legal(pos)
        for (m in order(pos, ml, 0)) {
            if (pos.b[moveTo(m)] == 0 && movePromo(m) == 0) continue
            val s = -quiesce(Rules.make(pos, m), -beta, -alpha, ply + 1)
            if (stop) return 0
            if (s >= beta) return s
            if (s > alpha) alpha = s
        }
        return alpha
    }

    private fun order(pos: Position, ml: MoveList, first: Int): IntArray {
        val n = ml.n
        val ms = ml.a.copyOf(n)
        val sc = IntArray(n)
        for (i in 0 until n) {
            val m = ms[i]
            var s = 0
            val victim = abs(pos.b[moveTo(m)])
            if (victim != 0) s += 1000 + 10 * victim - abs(pos.b[moveFrom(m)])
            if (movePromo(m) != 0) s += 900
            if (m == first) s += 100000
            sc[i] = s
        }
        for (i in 1 until n) {
            val m = ms[i]
            val s = sc[i]
            var j = i - 1
            while (j >= 0 && sc[j] < s) {
                ms[j + 1] = ms[j]
                sc[j + 1] = sc[j]
                j--
            }
            ms[j + 1] = m
            sc[j + 1] = s
        }
        return ms
    }

    private fun evaluate(pos: Position): Int {
        var s = 0
        val b = pos.b
        for (sq in 0..63) {
            val p = b[sq]
            if (p == 0) continue
            val t = abs(p)
            val r = sq / 8
            val f = sq % 8
            val idx = if (p > 0) (7 - r) * 8 + f else r * 8 + f
            val v = VAL[t] + pst(t, idx)
            s += if (p > 0) v else -v
        }
        return s * pos.side
    }

    private fun pst(t: Int, idx: Int): Int = when (t) {
        PAWN -> PST_P[idx]
        KNIGHT -> PST_N[idx]
        BISHOP -> PST_B[idx]
        KING -> PST_K[idx]
        else -> 0
    }

    companion object {
        const val MATE = 30000
        const val INF = 32000
        private val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)

        private val PST_P = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        private val PST_N = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50
        )
        private val PST_B = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
        )
        private val PST_K = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20
        )
    }
}
