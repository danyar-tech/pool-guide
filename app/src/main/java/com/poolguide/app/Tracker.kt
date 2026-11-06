package com.poolguide.app

/**
 * Keeps ball identities stable between frames and stops flicker:
 *  - a ball missed for up to [maxMisses] frames is kept while the table is still
 *  - its type is a majority vote over recent frames
 *  - large movement marks the table as "moving" (shots are hidden until it settles)
 */
class Tracker(private val maxMisses: Int = 3) {

    private class Track(
        val id: Int,
        var pos: Vec,
        var misses: Int = 0,
        var hits: Int = 1,
        val votes: ArrayDeque<BallKind> = ArrayDeque(),
    ) {
        fun vote(k: BallKind) {
            votes.addLast(k)
            while (votes.size > 7) votes.removeFirst()
        }
        fun kind(): BallKind {
            val known = votes.filter { it != BallKind.UNKNOWN }
            if (known.isEmpty()) return BallKind.UNKNOWN
            return known.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        }
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    var moving = false
        private set

    fun reset() { tracks.clear(); moving = false }

    fun update(detected: List<Ball>, r: Double): List<Ball> {
        val unmatched = detected.toMutableList()
        var bigMoves = 0
        var matched = 0
        // Greedy nearest matching, closest pairs first.
        val pairs = ArrayList<Triple<Track, Ball, Double>>()
        for (t in tracks) for (b in detected) {
            val d = t.pos.dist(b.pos)
            if (d < r * 1.2) pairs += Triple(t, b, d)
        }
        pairs.sortBy { it.third }
        val usedT = HashSet<Int>(); val usedB = HashSet<Ball>()
        for ((t, b, d) in pairs) {
            if (t.id in usedT || b in usedB) continue
            usedT += t.id; usedB += b
            unmatched.remove(b)
            matched++
            if (d > r * 0.3) bigMoves++
            // light smoothing for still balls; follow moving balls exactly
            t.pos = if (d < r * 0.15) t.pos * 0.5 + b.pos * 0.5 else b.pos
            t.misses = 0; t.hits++
            t.vote(b.kind)
        }
        val newCount = unmatched.size
        val lost = tracks.count { it.id !in usedT }
        moving = bigMoves > 0 || (tracks.isNotEmpty() && (newCount + lost) > 2)

        // Unmatched tracks: keep briefly if the table is still, else drop.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.id in usedT) continue
            t.misses++
            if (moving || t.misses > maxMisses) it.remove()
        }
        for (b in unmatched) tracks += Track(nextId++, b.pos).also { it.vote(b.kind) }

        // Only one cue ball and one eight: keep the strongest votes.
        val out = tracks.map { Ball(it.id, it.pos, it.kind()) }.toMutableList()
        fun dedupe(kind: BallKind) {
            val list = out.filter { it.kind == kind }
            if (list.size <= 1) return
            val keep = list.maxByOrNull { b -> tracks.first { it.id == b.id }.votes.count { it == kind } }
            list.forEach { if (it !== keep) it.kind = BallKind.UNKNOWN }
        }
        dedupe(BallKind.CUE); dedupe(BallKind.EIGHT)
        return out
    }
}
