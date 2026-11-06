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
    // Table auto-detection (optional; the table is normally set by hand). Balls: see BallVision.kt.

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

}
