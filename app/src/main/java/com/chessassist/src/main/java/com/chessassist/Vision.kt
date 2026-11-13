package com.chessassist

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FrameBuf {
    var data = ByteArray(0)
    var w = 0
    var h = 0
    var stride = 0
    var seq = 0L
    var valid = false
}

class Geometry(val x0: Float, val y0: Float, val side: Float) {
    val sq: Float get() = side / 8f
}

class Templates {
    val feats = ArrayList<IntArray>()
    val codes = ArrayList<Int>()
}

object Vision {
    const val G = 16
    private const val THR = 22
    private const val EMPTY_CELLS = 24
    private const val MAX_DIST = 90
    private const val MAX_SELF_DIST = 120

    private fun rgb(f: FrameBuf, x: Int, y: Int): Int {
        val xx = x.coerceIn(0, f.w - 1)
        val yy = y.coerceIn(0, f.h - 1)
        val i = yy * f.stride + xx * 4
        return ((f.data[i].toInt() and 255) shl 16) or
            ((f.data[i + 1].toInt() and 255) shl 8) or
            (f.data[i + 2].toInt() and 255)
    }

    private fun med4(a: Int, b: Int, c: Int, d: Int): Int =
        (a + b + c + d - min(min(a, b), min(c, d)) - max(max(a, b), max(c, d))) / 2

    fun isBlack(f: FrameBuf): Boolean {
        if (!f.valid) return false
        for (i in 1..12) {
            for (j in 1..24) {
                if (rgb(f, f.w * i / 13, f.h * j / 25) != 0) return false
            }
        }
        return true
    }

    // 0 = square background, 1 = dark piece pixel, 2 = light piece pixel.
    fun feature(f: FrameBuf, gx: Float, gy: Float, s: Float): IntArray {
        val ins = 0.12f
        val c0 = rgb(f, (gx + s * ins).toInt(), (gy + s * ins).toInt())
        val c1 = rgb(f, (gx + s * (1 - ins)).toInt(), (gy + s * ins).toInt())
        val c2 = rgb(f, (gx + s * ins).toInt(), (gy + s * (1 - ins)).toInt())
        val c3 = rgb(f, (gx + s * (1 - ins)).toInt(), (gy + s * (1 - ins)).toInt())
        val bgR = med4((c0 shr 16) and 255, (c1 shr 16) and 255, (c2 shr 16) and 255, (c3 shr 16) and 255)
        val bgG = med4((c0 shr 8) and 255, (c1 shr 8) and 255, (c2 shr 8) and 255, (c3 shr 8) and 255)
        val bgB = med4(c0 and 255, c1 and 255, c2 and 255, c3 and 255)
        val out = IntArray(G * G)
        for (cy in 0 until G) {
            for (cx in 0 until G) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (sy in 0..1) {
                    for (sx in 0..1) {
                        val u = 0.10f + 0.80f * ((cx + 0.25f + 0.5f * sx) / G)
                        val v = 0.10f + 0.80f * ((cy + 0.25f + 0.5f * sy) / G)
                        val c = rgb(f, (gx + s * u).toInt(), (gy + s * v).toInt())
                        sr += (c shr 16) and 255
                        sg += (c shr 8) and 255
                        sb += c and 255
                    }
                }
                sr /= 4
                sg /= 4
                sb /= 4
                val d = max(abs(sr - bgR), max(abs(sg - bgG), abs(sb - bgB)))
                out[cy * G + cx] = if (d <= THR) {
                    0
                } else {
                    val lum = (299 * sr + 587 * sg + 114 * sb) / 1000
                    if (lum >= 128) 2 else 1
                }
            }
        }
        return out
    }

    private fun dist(a: IntArray, b: IntArray): Int {
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d
    }

    fun boardIndex(r: Int, c: Int, flipped: Boolean): Int {
        val rank = if (!flipped) 7 - r else r
        val file = if (!flipped) c else 7 - c
        return rank * 8 + file
    }

    private fun classify(feat: IntArray, t: Templates): Int? {
        var cover = 0
        for (v in feat) if (v != 0) cover++
        if (cover < EMPTY_CELLS) return 0
        var best = Int.MAX_VALUE
        var code = 0
        for (i in t.feats.indices) {
            val d = dist(feat, t.feats[i])
            if (d < best) {
                best = d
                code = t.codes[i]
            }
        }
        return if (best <= MAX_DIST) code else null
    }

    fun observe(f: FrameBuf, g: Geometry, flipped: Boolean, t: Templates): IntArray? {
        val out = IntArray(64)
        for (r in 0..7) {
            for (c in 0..7) {
                val feat = feature(f, g.x0 + c * g.sq, g.y0 + r * g.sq, g.sq)
                val code = classify(feat, t) ?: return null
                out[boardIndex(r, c, flipped)] = code
            }
        }
        return out
    }

    fun learn(f: FrameBuf, g: Geometry, flipped: Boolean): Pair<Templates?, String?> {
        val init = Rules.initial().b
        val t = Templates()
        for (r in 0..7) {
            for (c in 0..7) {
                val idx = boardIndex(r, c, flipped)
                val code = init[idx]
                val feat = feature(f, g.x0 + c * g.sq, g.y0 + r * g.sq, g.sq)
                var cover = 0
                for (v in feat) if (v != 0) cover++
                if (code == 0) {
                    if (cover >= EMPTY_CELLS) {
                        return Pair(null, "Casa ${sqName(idx)} deveria estar vazia, mas tem conteúdo. Cantos mal marcados, ou o tabuleiro não está na posição inicial.")
                    }
                } else {
                    if (cover < EMPTY_CELLS) {
                        return Pair(null, "Não vi peça em ${sqName(idx)}. Cantos mal marcados, ou lado errado (Brancas/Pretas).")
                    }
                    t.feats.add(feat)
                    t.codes.add(code)
                }
            }
        }
        for (i in t.feats.indices) {
            for (j in i + 1 until t.feats.size) {
                if (t.codes[i] == t.codes[j] && dist(t.feats[i], t.feats[j]) > MAX_SELF_DIST) {
                    return Pair(null, "Peças iguais não parecem iguais (tema com textura ou cantos imprecisos). Refaça os cantos.")
                }
            }
        }
        return Pair(t, null)
    }
}
