package com.poolguide.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Felt colour in OpenCV HSV (H 0..180, S/V 0..255) plus RGB for low-saturation felts. */
data class Felt(val h: Int, val s: Int, val v: Int, val r: Int, val g: Int, val b: Int)

data class BallDetection(val balls: List<Ball>, val radius: Double)

/**
 * Computer vision for top-down pool games:
 *  - table: dominant felt colour -> largest felt region -> inner cushion edge -> dark pocket holes
 *  - balls: non-felt mask + distance transform + Hough circles, merged and validated
 *  - classification: white / black / coloured pixel fractions -> cue, eight, solid, stripe
 */
class Vision {

    // ------------------------------------------------------------------ helpers

    private class Hsv(val w: Int, val h: Int, val hsv: ByteArray, val rgb: ByteArray)

    private fun toHsv(rgba: Mat): Hsv {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val w = rgb.cols()
        val h = rgb.rows()
        val hb = ByteArray(w * h * 3)
        val rb = ByteArray(w * h * 3)
        hsv.get(0, 0, hb)
        rgb.get(0, 0, rb)
        rgb.release(); hsv.release()
        return Hsv(w, h, hb, rb)
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun hueDist(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 180 - d)
    }

    private fun resizeTo(src: Mat, maxDim: Int): Pair<Mat, Double> {
        val s = min(1.0, maxDim.toDouble() / max(src.cols(), src.rows()))
        val dst = Mat()
        if (s < 1.0) {
            Imgproc.resize(src, dst, Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            src.copyTo(dst)
        }
        return dst to s
    }

    /** Dominant felt colour of the region (cx0..cx1, cy0..cy1) of an HSV image. */
    private fun estimateFelt(img: Hsv, x0: Int, y0: Int, x1: Int, y1: Int): Felt? {
        val hist = IntArray(180)
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = (y * img.w + x) * 3
            val s = u(img.hsv[i + 1]); val v = u(img.hsv[i + 2])
            if (s > 50 && v > 40) { hist[u(img.hsv[i]) % 180]++; n++ }
        }
        if (n < 50) return null
        // circular smoothing
        var best = 0; var bestVal = -1
        for (hh in 0 until 180) {
            var acc = 0
            for (k in -3..3) acc += hist[(hh + k + 180) % 180]
            if (acc > bestVal) { bestVal = acc; best = hh }
        }
        val ss = ArrayList<Int>(); val vs = ArrayList<Int>()
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val i = (y * img.w + x) * 3
            if (hueDist(u(img.hsv[i]), best) <= 6 && u(img.hsv[i + 1]) > 50 && u(img.hsv[i + 2]) > 40) {
                ss += u(img.hsv[i + 1]); vs += u(img.hsv[i + 2])
                rs += u(img.rgb[i]); gs += u(img.rgb[i + 1]); bs += u(img.rgb[i + 2])
            }
        }
        if (ss.size < 20) return null
        return Felt(best, median(ss), median(vs), median(rs), median(gs), median(bs))
    }

    private fun median(l: MutableList<Int>): Int { l.sort(); return l[l.size / 2] }

    /** 255 where the pixel looks like felt. */
    private fun feltMask(img: Hsv, f: Felt, tight: Boolean): ByteArray {
        val out = ByteArray(img.w * img.h)
        val hueTol = if (tight) 7 else 12
        val sMin = if (tight) max(40, (f.s * 0.6).toInt()) else max(30, (f.s * 0.45).toInt())
        val vLo = if (tight) (f.v * 0.72).toInt() else max(25, (f.v * 0.35).toInt())
        val vHi = if (tight) (f.v * 1.3).toInt() else 256
        val lowSat = f.s < 60
        val rgbTol = if (tight) 30 else 48
        for (p in 0 until img.w * img.h) {
            val i = p * 3
            val isFelt = if (lowSat) {
                val dr = u(img.rgb[i]) - f.r; val dg = u(img.rgb[i + 1]) - f.g; val db = u(img.rgb[i + 2]) - f.b
                dr * dr + dg * dg + db * db < rgbTol * rgbTol
            } else {
                val v = u(img.hsv[i + 2])
                hueDist(u(img.hsv[i]), f.h) <= hueTol && u(img.hsv[i + 1]) >= sMin && v >= vLo && v <= vHi
            }
            if (isFelt) out[p] = 255.toByte()
        }
        return out
    }

    private fun toMat(bytes: ByteArray, w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC1)
        m.put(0, 0, bytes)
        return m
    }

    private fun kernel(size: Int): Mat {
        val k = max(1, size) or 1
        return Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
    }

    // ------------------------------------------------------------------ table

    fun detectTable(rgba: Mat): TableGeom? {
        val (small, s) = resizeTo(rgba, 640)
        val img = toHsv(small)
        small.release()
        val w = img.w; val h = img.h
        val felt = estimateFelt(img, w / 4, h / 4, w * 3 / 4, h * 3 / 4) ?: return null

        // Outer felt region (cushions included when they share the felt colour).
        val loose = toMat(feltMask(img, felt, false), w, h)
        Imgproc.morphologyEx(loose, loose, Imgproc.MORPH_CLOSE, kernel(min(w, h) / 22))
        Imgproc.morphologyEx(loose, loose, Imgproc.MORPH_OPEN, kernel(5))
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(loose, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release(); loose.release()
        val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(biggest) < 0.10 * w * h) return null
        val outer: Rect = Imgproc.boundingRect(biggest)

        // Inner playing surface: walk outwards from the centre on a tight felt mask until rows/cols stop being felt.
        val tightBytes = feltMask(img, felt, true)
        val tight = toMat(tightBytes, w, h)
        Imgproc.morphologyEx(tight, tight, Imgproc.MORPH_CLOSE, kernel(min(w, h) / 18))
        tight.get(0, 0, tightBytes)
        tight.release()
        val ox0 = outer.x; val oy0 = outer.y; val ox1 = outer.x + outer.width; val oy1 = outer.y + outer.height
        fun rowFrac(y: Int): Double {
            var c = 0
            for (x in ox0 until ox1) if (tightBytes[y * w + x] != 0.toByte()) c++
            return c.toDouble() / max(1, ox1 - ox0)
        }
        fun colFrac(x: Int): Double {
            var c = 0
            for (y in oy0 until oy1) if (tightBytes[y * w + x] != 0.toByte()) c++
            return c.toDouble() / max(1, oy1 - oy0)
        }
        fun walk(from: Int, to: Int, step: Int, frac: (Int) -> Double): Int {
            var i = from; var misses = 0; var last = from
            while (i != to) {
                if (frac(i) >= 0.45) { last = i; misses = 0 } else if (++misses >= 3) break
                i += step
            }
            return last
        }
        val cy = (oy0 + oy1) / 2; val cx = (ox0 + ox1) / 2
        var top = walk(cy, oy0 - 1, -1, ::rowFrac)
        var bottom = walk(cy, oy1, 1, ::rowFrac) + 1
        var left = walk(cx, ox0 - 1, -1, ::colFrac)
        var right = walk(cx, ox1, 1, ::colFrac) + 1
        if (right - left < outer.width * 0.7 || bottom - top < outer.height * 0.7) {
            left = ox0; top = oy0; right = ox1; bottom = oy1
        }

        val table = TableGeom(left / s, top / s, right / s, bottom / s)
        table.pocketRadius = 0.0
        table.resetPockets()

        // Refine pockets: centroid of dark pixels near each expected pocket position.
        val short = min(right - left, bottom - top).toDouble()
        val win = (short * 0.09).roundToInt()
        val darkMax = min(70, (felt.v * 0.45).toInt())
        val radii = ArrayList<Double>()
        for (p in table.pockets) {
            val ex = (p.pos.x * s).roundToInt(); val ey = (p.pos.y * s).roundToInt()
            var sx = 0.0; var sy = 0.0; var n = 0
            for (y in max(0, ey - win) until min(h, ey + win)) for (x in max(0, ex - win) until min(w, ex + win)) {
                val i = (y * w + x) * 3
                if (u(img.hsv[i + 2]) < darkMax) { sx += x; sy += y; n++ }
            }
            val minArea = PI * sq(short * 0.012)
            if (n > minArea) {
                val c = Vec(sx / n, sy / n)
                // Only accept if the dark blob is reasonably close to where a pocket should be.
                if (c.dist(Vec(ex.toDouble(), ey.toDouble())) < win * 0.8) {
                    p.pos = Vec(c.x / s, c.y / s)
                    radii += sqrt(n / PI) / s
                }
            }
        }
        val shortFull = table.shortSide
        table.pocketRadius = if (radii.isNotEmpty()) {
            radii.sort(); clampD(radii[radii.size / 2], shortFull * 0.025, shortFull * 0.07)
        } else shortFull * 0.045
        return table
    }

    // ------------------------------------------------------------------ balls

    /**
     * @param radiusOverride ball radius in screen px chosen by the user (0 = automatic)
     * @param radiusHint radius from the previous frame (0 = none), used to stabilise the estimate
     */
    fun detectBalls(rgba: Mat, table: TableGeom, radiusOverride: Double, radiusHint: Double): BallDetection? {
        val x0 = clampD(floor(table.left), 0.0, rgba.cols() - 2.0).toInt()
        val y0 = clampD(floor(table.top), 0.0, rgba.rows() - 2.0).toInt()
        val x1 = clampD(ceil(table.right), x0 + 1.0, rgba.cols().toDouble()).toInt()
        val y1 = clampD(ceil(table.bottom), y0 + 1.0, rgba.rows().toDouble()).toInt()
        if (x1 - x0 < 50 || y1 - y0 < 50) return null
        val crop = rgba.submat(Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        val (small, s) = resizeTo(crop, 960)
        crop.release()
        val img = toHsv(small)
        val w = img.w; val h = img.h
        val felt = estimateFelt(img, 0, 0, w, h)
        if (felt == null) { small.release(); return null }

        // Non-felt mask, with the pocket holes removed.
        val feltBytes = feltMask(img, felt, false)
        val nf = ByteArray(w * h)
        for (i in nf.indices) nf[i] = if (feltBytes[i] == 0.toByte()) 255.toByte() else 0.toByte()
        val pr = table.pocketRadius * s * 1.05
        for (p in table.pockets) {
            val px = (p.pos.x - x0) * s; val py = (p.pos.y - y0) * s
            for (y in max(0, (py - pr).toInt()) until min(h, (py + pr).toInt() + 1))
                for (x in max(0, (px - pr).toInt()) until min(w, (px + pr).toInt() + 1))
                    if (sq(x - px) + sq(y - py) <= pr * pr) nf[y * w + x] = 0.toByte()
        }
        val nfMat = toMat(nf, w, h)
        Imgproc.morphologyEx(nfMat, nfMat, Imgproc.MORPH_OPEN, kernel(3))
        nfMat.get(0, 0, nf)

        val dist = Mat()
        Imgproc.distanceTransform(nfMat, dist, Imgproc.DIST_L2, 5)
        val distArr = FloatArray(w * h)
        dist.get(0, 0, distArr)

        // ---- radius estimate from isolated blobs
        var r: Double
        if (radiusOverride > 0) {
            r = radiusOverride * s
        } else {
            val labels = Mat(); val stats = Mat(); val cents = Mat()
            val n = Imgproc.connectedComponentsWithStats(nfMat, labels, stats, cents)
            val lab = IntArray(w * h)
            labels.get(0, 0, lab)
            val maxD = FloatArray(n)
            for (i in lab.indices) { val l = lab[i]; if (l > 0 && distArr[i] > maxD[l]) maxD[l] = distArr[i] }
            val est = ArrayList<Double>()
            for (l in 1 until n) {
                val area = stats.get(l, Imgproc.CC_STAT_AREA)[0]
                val rr = maxD[l].toDouble()
                if (rr < 4) continue
                val ratio = area / (PI * rr * rr)
                if (ratio in 0.75..1.6) est += rr
            }
            labels.release(); stats.release(); cents.release()
            r = if (est.isNotEmpty()) { est.sort(); est[est.size / 2] } else 0.0
            val hint = radiusHint * s
            r = when {
                r <= 0 && hint > 0 -> hint
                r <= 0 -> min(w, h) * 0.035
                hint > 0 -> 0.6 * hint + 0.4 * r
                else -> r
            }
        }

        data class Cand(var x: Double, var y: Double, val score: Double)
        val cands = ArrayList<Cand>()

        fun nonFeltFraction(cx: Double, cy: Double, rad: Double): Double {
            var tot = 0; var hit = 0
            val r2 = rad * rad
            for (y in max(0, (cy - rad).toInt()) until min(h, (cy + rad).toInt() + 1))
                for (x in max(0, (cx - rad).toInt()) until min(w, (cx + rad).toInt() + 1)) {
                    if (sq(x - cx) + sq(y - cy) > r2) continue
                    tot++
                    if (nf[y * w + x] != 0.toByte()) hit++
                }
            return if (tot == 0) 0.0 else hit.toDouble() / tot
        }

        // ---- Hough circles (good centres, handles clusters)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.medianBlur(gray, gray, 5)
        val circles = Mat()
        try {
            Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT_ALT, 1.5, r * 1.5, 300.0, 0.75,
                floor(r * 0.75).toInt().coerceAtLeast(3), ceil(r * 1.3).toInt())
            for (i in 0 until circles.cols()) {
                val c = circles.get(0, i) ?: continue
                val f = nonFeltFraction(c[0], c[1], r * 0.8)
                if (f >= 0.7) cands += Cand(c[0], c[1], 2.0 + f)
            }
        } catch (e: Exception) {
            // Hough can fail on unusual images; distance-transform peaks below still work.
        }
        gray.release(); circles.release()

        // ---- distance transform peaks (robust for isolated and touching balls)
        val dil = Mat()
        Imgproc.dilate(dist, dil, kernel((r * 1.6).roundToInt()))
        val dilArr = FloatArray(w * h)
        dil.get(0, 0, dilArr)
        dil.release(); dist.release(); nfMat.release()
        val peaks = ArrayList<Cand>()
        val minPeak = r * 0.72
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val d = distArr[i]
            if (d >= minPeak && d >= dilArr[i] - 1e-3) peaks += Cand(x.toDouble(), y.toDouble(), d / r)
        }
        peaks.sortByDescending { it.score }
        for (p in peaks) {
            if (cands.none { sq(it.x - p.x) + sq(it.y - p.y) < sq(r * 1.3) }) cands += p
        }

        // ---- validate, dedupe
        cands.sortByDescending { it.score }
        val kept = ArrayList<Cand>()
        for (c in cands) {
            if (c.x < r * 0.55 || c.y < r * 0.55 || c.x > w - r * 0.55 || c.y > h - r * 0.55) continue
            if (table.pockets.any { sq((it.pos.x - x0) * s - c.x) + sq((it.pos.y - y0) * s - c.y) < sq(table.pocketRadius * s) }) continue
            if (kept.any { sq(it.x - c.x) + sq(it.y - c.y) < sq(r * 1.4) }) continue
            kept += c
            if (kept.size >= 22) break
        }

        // ---- classify
        data class Stats(val white: Double, val black: Double, val color: Double, val hue: Float)
        fun statsAt(cx: Double, cy: Double): Stats {
            val rad = r * 0.82
            var tot = 0; var wh = 0; var bl = 0; var co = 0
            val hues = ArrayList<Int>()
            for (y in max(0, (cy - rad).toInt()) until min(h, (cy + rad).toInt() + 1))
                for (x in max(0, (cx - rad).toInt()) until min(w, (cx + rad).toInt() + 1)) {
                    if (sq(x - cx) + sq(y - cy) > rad * rad) continue
                    val i = (y * w + x) * 3
                    val hh = u(img.hsv[i]); val ss = u(img.hsv[i + 1]); val vv = u(img.hsv[i + 2])
                    tot++
                    when {
                        ss < 60 && vv > 165 -> wh++
                        vv < 60 -> bl++
                        ss >= 80 && vv >= 60 && hueDist(hh, felt.h) > 10 -> { co++; hues += hh }
                    }
                }
            val t = max(1, tot).toDouble()
            val hue = if (hues.isEmpty()) -1f else { hues.sort(); hues[hues.size / 2].toFloat() }
            return Stats(wh / t, bl / t, co / t, hue)
        }
        val st = kept.map { statsAt(it.x, it.y) }
        val kinds = Array(kept.size) { BallKind.UNKNOWN }
        val cueIdx = st.indices.filter { st[it].white > 0.55 && st[it].color < 0.2 }.maxByOrNull { st[it].white - st[it].color }
        if (cueIdx != null) kinds[cueIdx] = BallKind.CUE
        val eightIdx = st.indices.filter { it != cueIdx && st[it].black > 0.45 }.maxByOrNull { st[it].black }
        if (eightIdx != null) kinds[eightIdx] = BallKind.EIGHT
        for (i in st.indices) {
            if (kinds[i] != BallKind.UNKNOWN) continue
            val q = st[i]
            kinds[i] = when {
                q.white >= 0.22 && q.color >= 0.15 -> BallKind.STRIPE
                q.color >= 0.35 -> BallKind.SOLID
                else -> BallKind.UNKNOWN
            }
        }
        small.release()

        val balls = kept.mapIndexed { i, c ->
            Ball(i, Vec(x0 + c.x / s, y0 + c.y / s), kinds[i], st[i].hue)
        }
        return BallDetection(balls, r / s)
    }
}
