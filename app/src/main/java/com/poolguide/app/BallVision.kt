package com.poolguide.app

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BallDetection(val balls: List<Ball>, val radius: Double)

/** Shot direction (radians, screen coordinates) read from the game's cue stick or aim line. */
data class StickReading(val angle: Double, val fromStick: Boolean)

/**
 * Ball and cue-stick detection (v2). Ported from a Python prototype that was tuned on
 * hundreds of synthetic top-down pool screenshots:
 *  - everything is computed at a scale where the ball radius is [TARGET_R] px
 *  - "difference from felt" map in Lab with a vignette-aware felt brightness background
 *  - a saturated silhouette map so the texture inside a ball (numbers, highlight) adds no edges
 *  - circle evidence = radial minus tangential gradient energy on a ring of radius R (linear filters)
 *  - candidates validated by edge coverage around the circle and a non-felt interior
 */
class BallVision {

    companion object {
        const val TARGET_R = 12.0
    }

    /** Per-frame data shared by ball and stick detection. */
    class Prep(
        val x0: Int, val y0: Int, val s: Double, val w: Int, val h: Int,
        val L: FloatArray, val A: FloatArray, val B: FloatArray,
        val Lbg: FloatArray, val D: FloatArray,
        val gx: FloatArray, val gy: FloatArray,
        val gxx: FloatArray, val gxy: FloatArray, val gyy: FloatArray,
    ) {
        fun toScreen(x: Double, y: Double) = Vec(x0 + x / s, y0 + y / s)
        fun toLocal(p: Vec) = Vec((p.x - x0) * s, (p.y - y0) * s)
    }

    // ------------------------------------------------------------------ mat helpers

    private fun toMat(a: FloatArray, w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_32FC1); m.put(0, 0, a); return m
    }

    private fun toArr(m: Mat): FloatArray {
        val a = FloatArray(m.rows() * m.cols()); m.get(0, 0, a); return a
    }

    private fun filter(a: FloatArray, w: Int, h: Int, k: Mat): FloatArray {
        val src = toMat(a, w, h); val dst = Mat()
        Imgproc.filter2D(src, dst, -1, k)
        val out = toArr(dst); src.release(); dst.release(); return out
    }

    private fun sobel(a: FloatArray, w: Int, h: Int, dx: Int, dy: Int): FloatArray {
        val src = toMat(a, w, h); val dst = Mat()
        Imgproc.Sobel(src, dst, CvType.CV_32F, dx, dy, 3)
        val out = toArr(dst); src.release(); dst.release(); return out
    }

    private fun kernelMat(k: Int, f: (Double, Double) -> Double): Mat {
        val n = 2 * k + 1
        val arr = FloatArray(n * n)
        for (y in -k..k) for (x in -k..k) arr[(y + k) * n + (x + k)] = f(x.toDouble(), y.toDouble()).toFloat()
        val m = Mat(n, n, CvType.CV_32FC1); m.put(0, 0, arr); return m
    }

    private fun diskKernel(rad: Double): Mat {
        val k = ceil(rad).toInt()
        var cnt = 0
        for (y in -k..k) for (x in -k..k) if (x * x + y * y <= rad * rad) cnt++
        return kernelMat(k) { x, y -> if (x * x + y * y <= rad * rad) 1.0 / cnt else 0.0 }
    }

    /** kxx, kxy, kyy (radial projection weights) and the plain ring, all normalised by the ring size. */
    private fun radialKernels(rad: Double, width: Double = 1.5): List<Mat> {
        val k = ceil(rad + width).toInt()
        var cnt = 0
        for (y in -k..k) for (x in -k..k) if (abs(sqrt((x * x + y * y).toDouble()) - rad) <= width) cnt++
        fun ring(x: Double, y: Double) = if (abs(sqrt(x * x + y * y) - rad) <= width) 1.0 / cnt else 0.0
        fun cs(x: Double, y: Double): Pair<Double, Double> {
            val d = max(sqrt(x * x + y * y), 1e-6); return (x / d) to (y / d)
        }
        return listOf(
            kernelMat(k) { x, y -> val (c, _) = cs(x, y); ring(x, y) * c * c },
            kernelMat(k) { x, y -> val (c, s) = cs(x, y); ring(x, y) * 2 * c * s },
            kernelMat(k) { x, y -> val (_, s) = cs(x, y); ring(x, y) * s * s },
            kernelMat(k) { x, y -> ring(x, y) },
        )
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    // ------------------------------------------------------------------ preparation

    fun prepare(rgba: Mat, table: TableGeom, radius: Double): Prep? {
        val x0 = clampD(table.left, 0.0, rgba.cols() - 2.0).toInt()
        val y0 = clampD(table.top, 0.0, rgba.rows() - 2.0).toInt()
        val x1 = clampD(table.right, x0 + 1.0, rgba.cols().toDouble()).toInt()
        val y1 = clampD(table.bottom, y0 + 1.0, rgba.rows().toDouble()).toInt()
        if (x1 - x0 < 40 || y1 - y0 < 40 || radius <= 1) return null
        val s = TARGET_R / radius
        val crop = rgba.submat(Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        val small = Mat()
        val sw = max(20, ((x1 - x0) * s).roundToInt()); val sh = max(20, ((y1 - y0) * s).roundToInt())
        Imgproc.resize(crop, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0,
            if (s < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        crop.release()
        val rgb = Mat(); Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB); small.release()
        val lab = Mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab); rgb.release()
        val w = lab.cols(); val h = lab.rows(); val n = w * h
        val bytes = ByteArray(n * 3); lab.get(0, 0, bytes); lab.release()
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n)
        for (i in 0 until n) {
            L[i] = u(bytes[3 * i]).toFloat()
            A[i] = u(bytes[3 * i + 1]) - 128f
            B[i] = u(bytes[3 * i + 2]) - 128f
        }

        // Felt chroma = mode of the (a,b) histogram.
        val hist = FloatArray(64 * 64)
        for (i in 0 until n) {
            val qa = ((A[i] + 128) / 4).toInt().coerceIn(0, 63)
            val qb = ((B[i] + 128) / 4).toInt().coerceIn(0, 63)
            hist[qa * 64 + qb] += 1f
        }
        val hm = toMat(hist, 64, 64)
        Imgproc.GaussianBlur(hm, hm, Size(5.0, 5.0), 1.0)
        val hs = toArr(hm); hm.release()
        var bi = 0
        for (i in hs.indices) if (hs[i] > hs[bi]) bi = i
        val a0 = (bi / 64) * 4 - 128 + 2f
        val b0 = (bi % 64) * 4 - 128 + 2f
        val feltish = FloatArray(n)
        val fas = ArrayList<Float>(); val fbs = ArrayList<Float>()
        for (i in 0 until n) {
            val da = A[i] - a0; val db = B[i] - b0
            if (da * da + db * db < 100f) {
                feltish[i] = 1f
                if (i % 3 == 0) { fas += A[i]; fbs += B[i] }
            }
        }
        val fa = if (fas.isEmpty()) a0 else fas.sorted()[fas.size / 2]
        val fb = if (fbs.isEmpty()) b0 else fbs.sorted()[fbs.size / 2]

        // Felt brightness background (handles vignette): normalised blur of L over felt-like pixels.
        val k = (TARGET_R * 8).toInt() or 1
        val lm = FloatArray(n) { L[it] * feltish[it] }
        fun blur(a: FloatArray): FloatArray {
            val m = toMat(a, w, h); val d = Mat()
            Imgproc.blur(m, d, Size(k.toDouble(), k.toDouble()))
            val r = toArr(d); m.release(); d.release(); return r
        }
        val num = blur(lm); val den = blur(feltish)
        val medL = L.copyOf().also { it.sort() }[n / 2]
        val Lbg = FloatArray(n) { if (den[it] > 1e-3f) num[it] / max(den[it], 1e-3f) else medL }
        val D = FloatArray(n)
        val F = FloatArray(n)
        for (i in 0 until n) {
            val da = A[i] - fa; val db = B[i] - fb
            val dab = sqrt(da * da + db * db)
            val dl = abs(L[i] - Lbg[i])
            D[i] = dab + 0.55f * max(dl - 6f, 0f)
            F[i] = min(D[i], 40f) / 40f * 100f
        }

        val gxL = sobel(L, w, h, 1, 0); val gyL = sobel(L, w, h, 0, 1)
        val gxA = sobel(A, w, h, 1, 0); val gyA = sobel(A, w, h, 0, 1)
        val gxB = sobel(B, w, h, 1, 0); val gyB = sobel(B, w, h, 0, 1)
        val fx = sobel(F, w, h, 1, 0); val fy = sobel(F, w, h, 0, 1)
        val gx = FloatArray(n); val gy = FloatArray(n)
        val gxx = FloatArray(n); val gxy = FloatArray(n); val gyy = FloatArray(n)
        val wl = 0.15f
        for (i in 0 until n) {
            gx[i] = gxL[i] + gxA[i] + gxB[i]
            gy[i] = gyL[i] + gyA[i] + gyB[i]
            gxx[i] = fx[i] * fx[i] + wl * (gxL[i] * gxL[i] + gxA[i] * gxA[i] + gxB[i] * gxB[i])
            gyy[i] = fy[i] * fy[i] + wl * (gyL[i] * gyL[i] + gyA[i] * gyA[i] + gyB[i] * gyB[i])
            gxy[i] = fx[i] * fy[i] + wl * (gxL[i] * gyL[i] + gxA[i] * gyA[i] + gxB[i] * gyB[i])
        }
        return Prep(x0, y0, s, w, h, L, A, B, Lbg, D, gx, gy, gxx, gxy, gyy)
    }

    // ------------------------------------------------------------------ balls

    private fun ringCoverage(p: Prep, cx: Double, cy: Double, rad: Double, n: Int = 36, thr: Float = 40f): Double {
        var hits = 0
        for (i in 0 until n) {
            val an = 2 * PI * i / n
            val ux = cos(an); val uy = sin(an)
            var best = 0.0
            for (dr in doubleArrayOf(-1.5, -0.75, 0.0, 0.75, 1.5)) {
                val xi = (cx + ux * (rad + dr)).roundToInt(); val yi = (cy + uy * (rad + dr)).roundToInt()
                if (xi in 0 until p.w && yi in 0 until p.h) {
                    val j = yi * p.w + xi
                    val g = abs(p.gx[j] * ux + p.gy[j] * uy)
                    if (g > best) best = g
                }
            }
            if (best > thr) hits++
        }
        return hits.toDouble() / n
    }

    private class Cand(val score: Float, val x: Int, val y: Int)
    private class Kept(val x: Double, val y: Double, val cov: Double)

    /** Detect balls inside the table rectangle. [radius] is the ball radius in screen pixels. */
    fun detect(rgba: Mat, table: TableGeom, radius: Double): Pair<BallDetection, Prep>? {
        val p = prepare(rgba, table, radius) ?: return null
        return detectIn(p, table, radius) to p
    }

    fun detectIn(p: Prep, table: TableGeom, radius: Double): BallDetection {
        val R = TARGET_R
        val w = p.w; val h = p.h
        val dm = filter(p.D, w, h, diskKernel(R * 0.7))
        val dc = filter(p.D, w, h, diskKernel(R * 0.45))
        val ks = radialKernels(R)
        val rad = filter(p.gxx, w, h, ks[0])
        val radXY = filter(p.gxy, w, h, ks[1])
        val radYY = filter(p.gyy, w, h, ks[2])
        val totIn = FloatArray(w * h) { p.gxx[it] + p.gyy[it] }
        val tot = filter(totIn, w, h, ks[3])
        ks.forEach { it.release() }
        val e = FloatArray(w * h)
        for (i in e.indices) {
            val r = rad[i] + radXY[i] + radYY[i]
            val tan = tot[i] - r
            e[i] = sqrt(max(r - tan, 0f))
        }
        // non-maximum suppression with a dilation
        val sMat = toMat(e, w, h); val dil = Mat()
        val kk = (R * 1.2).toInt() * 2 + 1
        Imgproc.dilate(sMat, dil, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kk.toDouble(), kk.toDouble())))
        val dArr = toArr(dil); sMat.release(); dil.release()
        val cands = ArrayList<Cand>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (e[i] >= dArr[i] - 1e-6f && e[i] / 60f > 0.5f) cands += Cand(e[i], x, y)
        }
        cands.sortByDescending { it.score }

        val pockets = table.pockets.map { p.toLocal(it.pos) }
        val prExcl = max(table.pocketRadius * p.s * 0.6, R * 0.5)
        val kept = ArrayList<Kept>()
        for (c in cands) {
            // refine: best E within +-2, then sub-pixel centroid
            var bx = c.x; var by = c.y; var bv = -1f
            for (dy in -2..2) for (dx in -2..2) {
                val xx = c.x + dx; val yy = c.y + dy
                if (xx in 0 until w && yy in 0 until h && e[yy * w + xx] > bv) { bv = e[yy * w + xx]; bx = xx; by = yy }
            }
            var mn = Float.MAX_VALUE
            for (yy in max(0, by - 2) until min(h, by + 3)) for (xx in max(0, bx - 2) until min(w, bx + 3)) mn = min(mn, e[yy * w + xx])
            var sw = 0.0; var sx = 0.0; var sy = 0.0
            for (yy in max(0, by - 2) until min(h, by + 3)) for (xx in max(0, bx - 2) until min(w, bx + 3)) {
                val v = (e[yy * w + xx] - mn).toDouble(); sw += v; sx += v * xx; sy += v * yy
            }
            val fx = if (sw > 0) sx / sw else bx.toDouble()
            val fy = if (sw > 0) sy / sw else by.toDouble()
            if (fx < R * 0.6 || fy < R * 0.6 || fx > w - R * 0.6 || fy > h - R * 0.6) continue
            if (pockets.any { Vec(fx, fy).dist(it) < prExcl }) continue
            if (kept.any { sq(it.x - fx) + sq(it.y - fy) < sq(R * 1.55) }) continue
            val cov = ringCoverage(p, fx, fy, R)
            val ix = fx.roundToInt().coerceIn(0, w - 1); val iy = fy.roundToInt().coerceIn(0, h - 1)
            val dmv = dm[iy * w + ix]; val dcv = dc[iy * w + ix]
            if (cov >= 0.6 && dmv >= 22f && dcv >= 15f) kept += Kept(fx, fy, cov)
            if (kept.size >= 22) break
        }

        val stats = kept.map { classify(p, it.x, it.y, R) }
        val kinds = assignKinds(stats)
        val balls = kept.mapIndexed { i, k -> Ball(i, p.toScreen(k.x, k.y), kinds[i]) }
        return BallDetection(balls, radius)
    }

    class BallStats(val white: Double, val whiteOuter: Double, val blackOuter: Double, val colour: Double)

    private fun classify(p: Prep, cx: Double, cy: Double, R: Double): BallStats {
        var nDisk = 0; var nOuter = 0; var wt = 0; var wo = 0; var bo = 0; var co = 0
        for (y in max(0, (cy - R).toInt()) until min(p.h, (cy + R).toInt() + 1))
            for (x in max(0, (cx - R).toInt()) until min(p.w, (cx + R).toInt() + 1)) {
                val d = sqrt(sq(x - cx) + sq(y - cy)) / R
                val i = y * p.w + x
                val l = p.L[i]; val ch = sqrt(p.A[i] * p.A[i] + p.B[i] * p.B[i])
                val white = l > 195f && ch < 22f
                val black = l < 70f && ch < 25f
                val colour = ch >= 25f && l > 35f
                if (d <= 0.8) { nDisk++; if (white) wt++; if (colour) co++ }
                if (d > 0.5 && d <= 0.85) { nOuter++; if (white) wo++; if (black) bo++ }
            }
        val nd = max(1, nDisk).toDouble(); val no = max(1, nOuter).toDouble()
        return BallStats(wt / nd, wo / no, bo / no, co / nd)
    }

    fun assignKinds(st: List<BallStats>): List<BallKind> {
        val kinds = MutableList(st.size) { BallKind.UNKNOWN }
        st.indices.filter { st[it].white > 0.6 && st[it].colour < 0.12 }
            .maxByOrNull { st[it].white - st[it].colour }?.let { kinds[it] = BallKind.CUE }
        st.indices.filter { kinds[it] == BallKind.UNKNOWN && st[it].blackOuter > 0.45 }
            .maxByOrNull { st[it].blackOuter }?.let { kinds[it] = BallKind.EIGHT }
        for (i in st.indices) {
            if (kinds[i] != BallKind.UNKNOWN) continue
            val s = st[i]
            kinds[i] = when {
                s.colour >= 0.12 && (s.whiteOuter >= 0.10 || s.colour < 0.62) -> BallKind.STRIPE
                s.colour >= 0.25 -> BallKind.SOLID
                else -> BallKind.UNKNOWN
            }
        }
        return kinds
    }

    /** Try a range of ball sizes and keep the one whose detections look most like full circles. */
    fun estimateRadius(rgba: Mat, table: TableGeom): Double? {
        val len = table.longSide
        var best: Pair<Double, Double>? = null
        fun score(R: Double): Double {
            val p = prepare(rgba, table, R) ?: return 0.0
            val det = detectIn(p, table, R)
            return det.balls.sumOf { val l = p.toLocal(it.pos); val c = ringCoverage(p, l.x, l.y, TARGET_R); c * c * c * c }
        }
        for (div in intArrayOf(100, 85, 72, 62, 54, 47, 41, 36)) {
            val R = len / div
            val sc = score(R)
            if (sc > 0 && (best == null || sc > best.first)) best = sc to R
        }
        val b = best ?: return null
        var result = b
        for (f in doubleArrayOf(0.88, 0.94, 1.06, 1.12)) {
            val R = b.second * f
            val sc = score(R)
            if (sc > result.first) result = sc to R
        }
        return result.second
    }

    // ------------------------------------------------------------------ cue stick / aim line

    private fun bilinear(img: FloatArray, p: Prep, x: Double, y: Double): Double {
        val xi = x.toInt(); val yi = y.toInt()
        if (x < 0 || y < 0 || xi >= p.w - 1 || yi >= p.h - 1) return 0.0
        val fx = x - xi; val fy = y - yi
        val i = yi * p.w + xi
        return img[i] * (1 - fx) * (1 - fy) + img[i + 1] * fx * (1 - fy) +
            img[i + p.w] * (1 - fx) * fy + img[i + p.w + 1] * fx * fy
    }

    fun detectStick(p: Prep, cue: Vec): StickReading? {
        val R = TARGET_R
        val c = p.toLocal(cue)
        val cx = c.x; val cy = c.y
        fun gblur(a: FloatArray, k: Int, sigma: Double): FloatArray {
            val m = toMat(a, p.w, p.h); val d = Mat()
            Imgproc.GaussianBlur(m, d, Size(k.toDouble(), k.toDouble()), sigma)
            val r = toArr(d); m.release(); d.release(); return r
        }
        val db = gblur(p.D, 5, 1.0)
        val lw = gblur(FloatArray(p.w * p.h) { max(p.L[it] - p.Lbg[it], 0f) }, 3, 0.8)

        fun ray(img: FloatArray, ang: Double, r0: Double, r1: Double, n: Int = 40): Double {
            val ux = cos(ang); val uy = sin(ang)
            val vals = ArrayList<Double>(n)
            for (i in 0 until n) {
                val d = r0 + (r1 - r0) * i / (n - 1)
                val x = cx + ux * d; val y = cy + uy * d
                if (x < 0 || y < 0 || x >= p.w || y >= p.h) break
                vals += bilinear(img, p, x, y)
            }
            if (vals.size < n / 3) return 0.0
            vals.sort()
            return vals[vals.size / 4] // lower quartile: the WHOLE ray must be stick/line, not one ball
        }

        var bestScore = 0.0; var bestAng = 0.0; var found = false
        for (i in 0 until 720) {
            val ang = 2 * PI * i / 720
            val st = ray(db, ang, R * 1.6, R * 9)
            val ln = ray(lw, ang, R * 1.4, R * 7) * 1.2
            if (st > bestScore) { bestScore = st; bestAng = ang; found = true }
            if (ln > bestScore) { bestScore = ln; bestAng = ang; found = true }
        }
        if (!found || bestScore < 12) return null
        var ang = bestAng
        val ux = cos(ang); val uy = sin(ang)

        // Thick bar = cue stick (shot goes the opposite way); thin line = the game's aim line.
        val widths = ArrayList<Double>()
        for (k in 0 until 10) {
            val dist = R * (2.2 + (9 - 2.2) * k / 9)
            val px = cx + ux * dist; val py = cy + uy * dist
            if (px < 0 || py < 0 || px >= p.w || py >= p.h) continue
            val prof = DoubleArray(51) { j -> val o = (-1.0 + 0.04 * j) * R; bilinear(p.D, p, px - uy * o, py + ux * o) }
            val mx = prof.maxOrNull() ?: 0.0
            if (mx > 0) widths += prof.count { it > mx * 0.5 } * 0.04
        }
        val isStick = widths.isNotEmpty() && widths.sorted()[widths.size / 2] > 0.25

        // Sub-pixel refinement: centroid of the cross profile at many distances + robust line fit.
        val img = if (isStick) p.D else lw
        val r0 = if (isStick) R * 2.0 else R * 1.6
        val r1 = if (isStick) R * 12 else R * 9
        val ds = ArrayList<Double>(); val os = ArrayList<Double>()
        for (i in 0 until 40) {
            val dist = r0 + (r1 - r0) * i / 39
            val px = cx + ux * dist; val py = cy + uy * dist
            if (px < R || py < R || px >= p.w - R || py >= p.h - R) break
            val offs = DoubleArray(41) { (-0.6 + 0.03 * it) * R }
            val prof = DoubleArray(41) { bilinear(img, p, px - uy * offs[it], py + ux * offs[it]) }
            val mx = prof.maxOrNull() ?: 0.0
            if (mx <= 8) continue
            var sw = 0.0; var so = 0.0
            for (j in offs.indices) { val wgt = max(prof[j] - mx * 0.5, 0.0); sw += wgt; so += wgt * offs[j] }
            if (sw > 0) { ds += dist; os += so / sw }
        }
        if (ds.size >= 6) {
            var d = ds.toDoubleArray(); var o = os.toDoubleArray()
            repeat(2) {
                val (a, b) = lineFit(d, o)
                val res = DoubleArray(d.size) { abs(o[it] - (a + b * d[it])) }
                val cut = res.sorted()[(res.size * 0.8).toInt().coerceAtMost(res.size - 1)] + 1e-6
                val keep = res.indices.filter { res[it] <= cut }
                d = DoubleArray(keep.size) { d[keep[it]] }; o = DoubleArray(keep.size) { o[keep[it]] }
            }
            if (d.size >= 3) {
                val (_, b) = lineFit(d, o)
                ang += atan(b)
            }
        }
        val shot = if (isStick) ang + PI else ang
        return StickReading(((shot % (2 * PI)) + 2 * PI) % (2 * PI), isStick)
    }

    /** Least squares o = a + b*d. */
    private fun lineFit(d: DoubleArray, o: DoubleArray): Pair<Double, Double> {
        val n = d.size.toDouble()
        val md = d.sum() / n; val mo = o.sum() / n
        var sdd = 0.0; var sdo = 0.0
        for (i in d.indices) { sdd += (d[i] - md) * (d[i] - md); sdo += (d[i] - md) * (o[i] - mo) }
        val b = if (sdd > 1e-9) sdo / sdd else 0.0
        return (mo - b * md) to b
    }
}
