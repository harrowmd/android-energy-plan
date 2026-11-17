package com.manytwo.energyplan

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wall types, in drawing priority order: where two walls meet at a corner,
 * the later one in this list colours the corner (so a façade stays red where
 * an internal wall joins it).
 */
enum class WallType(val label: String, val defaultThickness: Double, val defaultColor: Int) {
    /** No wall: two rooms that are really one space (a wall knocked through). 0 mm, drawn as a dotted line. */
    VIRTUAL("Virtual", 0.0, 0xFFFBC02D.toInt()),
    INTERNAL("Internal", 0.12, 0xFF388E3C.toInt()),
    STUD("Stud", 0.10, 0xFF1976D2.toInt()),
    GLAZING("Glazing", 0.08, 0xFFEC407A.toInt()),
    SHELTERED("Sheltered", 0.15, 0xFF7B1FA2.toInt()),
    PARTY("Party", 0.30, 0xFFF57C00.toInt()),
    EXTERNAL("External", 0.35, 0xFFD32F2F.toInt());

    companion object {
        /** The order users see them in. */
        val menuOrder = listOf(EXTERNAL, INTERNAL, SHELTERED, PARTY, STUD, GLAZING, VIRTUAL)

        fun parse(s: String) = entries.firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) }
    }
}

/** Wall colours chosen in Settings, shared by the screen and the JPG export. */
object WallColors {
    private val colors = WallType.entries.associateWith { it.defaultColor }.toMutableMap()

    operator fun get(t: WallType) = colors.getValue(t)

    fun load(prefs: SharedPreferences) {
        for (t in WallType.entries) colors[t] = prefs.getInt(prefKey(t), t.defaultColor)
    }

    fun set(prefs: SharedPreferences, t: WallType, color: Int) {
        colors[t] = color
        prefs.edit().putInt(prefKey(t), color).apply()
    }

    private fun prefKey(t: WallType) = "wallColor_${t.name}"
}

/** A wall the user has changed from its automatic type and thickness. */
data class WallSpec(val type: WallType, val thickness: Double)

enum class Side(val label: String) {
    TOP("top"), RIGHT("right"), BOTTOM("bottom"), LEFT("left");

    val horizontal get() = this == TOP || this == BOTTOM
    val opposite
        get() = when (this) {
            TOP -> BOTTOM; BOTTOM -> TOP; LEFT -> RIGHT; RIGHT -> LEFT
        }
}

/**
 * One straight run of wall on one [side] of [room], from [from] to [to] (x for
 * top/bottom walls, y for left/right ones), facing [neighbour] - or the
 * outside when that's null.
 *
 * Room sizes are internal, but rooms that share a wall are drawn touching, so
 * the plan stays neatly aligned: a shared wall is drawn centred on the join
 * (half its thickness in each room). A wall with nothing beyond it wraps
 * around the outside of the room, from its face outwards.
 */
class Wall(
    val key: String,
    val room: Room,
    val side: Side,
    val neighbour: Room?,
    val from: Double,
    val to: Double,
    val type: WallType,
    val thickness: Double,
    /** True when the user has changed it; false when type and thickness are automatic. */
    val custom: Boolean,
    /** Space between this room's face and the neighbour's (0 when they touch). */
    val gap: Double = 0.0,
    /** Extra length from bay windows on this stretch: each bay's outline less the width it replaces. */
    val bayExtra: Double = 0.0,
    /** The type the app picks by itself for this stretch. */
    val auto: WallType = if (neighbour == null) WallType.EXTERNAL else WallType.INTERNAL,
) {
    /** Along the room's face, plus the extra round any bay windows. */
    val length get() = round3(to - from + bayExtra)

    /** The bays on this stretch of wall (outside walls only). */
    val bays get() = if (neighbour != null) emptyList() else room.bays.filter { b ->
        b.side == side && b.span(room).let { (a, z) -> (a + z) / 2 in from..to }
    }

    /** The room's face: y for top/bottom walls, x for left/right ones. */
    val face
        get() = when (side) {
            Side.TOP -> room.y
            Side.BOTTOM -> room.y + room.depth
            Side.LEFT -> room.x
            Side.RIGHT -> room.x + room.width
        }

    /** x0, y0, x1, y1 of the wall's footprint: centred on the join, or outside the room. */
    val rect: DoubleArray
        get() {
            // A shared wall ending at the room's corner runs on by half its thickness, so corners close up.
            val (lo, hi) = if (side.horizontal) room.x to room.x + room.width else room.y to room.y + room.depth
            val ext = if (neighbour != null) thickness / 2 else 0.0
            val from = if (abs(from - lo) < EPS) from - ext else from
            val to = if (abs(to - hi) < EPS) to + ext else to
            val out = if (side == Side.TOP || side == Side.LEFT) -1 else 1
            val (inner, outer) = if (neighbour != null) {
                val centre = face + out * gap / 2
                centre - out * thickness / 2 to centre + out * thickness / 2
            } else {
                face to face + out * thickness
            }
            val a = min(inner, outer)
            val b = max(inner, outer)
            return if (side.horizontal) doubleArrayOf(from, a, to, b) else doubleArrayOf(a, from, b, to)
        }

    /** End points of the wall along the room's face. */
    val ends: DoubleArray
        get() = if (side.horizontal) doubleArrayOf(from, face, to, face) else doubleArrayOf(face, from, face, to)

    fun describe() = "${room.floor.title}: ${room.name} ${side.label} wall" + (neighbour?.let { " (to ${it.name})" } ?: "") +
        ", ${type.label} ${mm(thickness)} mm, ${fmtM(length)} m"
}

/** The square where two outside walls of a room meet at its corner. */
class WallCorner(val room: Room, val rect: DoubleArray, val type: WallType)

/** An end of a square bay with its own wall type (e.g. Party, where it meets the neighbour's bay). */
class BayEnd(val room: Room, val bay: Bay, val atStart: Boolean, val type: WallType, val thickness: Double, val length: Double)

class WallLayout(val walls: List<Wall>, val corners: List<WallCorner>, val bayEnds: List<BayEnd> = emptyList()) {
    /** Just the walls of rooms on [floor]. */
    fun on(floor: Floor) = WallLayout(
        walls.filter { it.room.floor == floor }, corners.filter { it.room.floor == floor }, bayEnds.filter { it.room.floor == floor },
    )

    /** How far shared walls reach into [r] across its [side], so its labels can keep clear. */
    fun intrusion(r: Room, side: Side): Double {
        val face = when (side) {
            Side.TOP -> r.y
            Side.BOTTOM -> r.y + r.depth
            Side.LEFT -> r.x
            Side.RIGHT -> r.x + r.width
        }
        return walls.filter { w ->
            w.neighbour != null && when {
                w.room === r -> w.side == side
                w.neighbour === r -> w.side == side.opposite && abs(w.face - face) <= w.gap + EPS
                else -> false
            }
        }.maxOfOrNull { max(0.0, (it.thickness - it.gap) / 2) } ?: 0.0
    }

    /** Total length per (type, thickness), in [WallType.menuOrder]. */
    fun totals(): List<Triple<WallType, Double, Double>> =
        (walls.map { Triple(it.type, it.thickness, it.length) } + bayEnds.map { Triple(it.type, it.thickness, it.length) })
            .groupBy { it.first to it.second }
            .map { (k, ws) -> Triple(k.first, k.second, round3(ws.sumOf { it.third })) }
            .sortedWith(compareBy({ WallType.menuOrder.indexOf(it.first) }, { it.second }))
}

/**
 * Rooms further apart than this don't share a wall. Rooms usually touch (snapping puts them edge
 * to edge), but a room fitted against the outside of another (e.g. an extension against the main
 * house's old outside wall) sits a wall's thickness away; that wall is now between the two rooms.
 */
const val MAX_SHARED_GAP = 0.6
private const val EPS = 1e-6
private const val MIN_WALL = 0.005

fun mm(m: Double) = Math.round(m * 1000).toString()

/**
 * Works out every wall of the plan. Each side of each room is first cut back
 * to the part no room above it covers, then split by what lies beyond it:
 * the nearest room within [MAX_SHARED_GAP] makes that stretch a shared Internal wall
 * (at least as thick as the gap it fills),
 * anything else is External. There's no wall between two unallocated
 * spaces. The user's changes in [Plan.wallOverrides] then
 * replace the automatic type and thickness.
 */
fun Plan.wallLayout(): WallLayout {
    val all = mutableListOf<Wall>()
    for (r in rooms) for (side in Side.entries) {
        for ((a, b) in uncoveredSpans(r, side)) {
            for ((s0, s1, nb) in neighbourSpans(r, side, a, b)) {
                val n = nb?.first
                val key = wallKey(r, side, n)
                // A short stretch facing the gap between two rooms is the end of the wall between them, not outside.
                val bridge = n == null && bridgesGap(r, side, s0, s1)
                val auto = if (n != null || bridge) WallType.INTERNAL else WallType.EXTERNAL
                // The side's setting was made for its outside wall (often just to keep its thickness when the
                // default changed); an External setting doesn't apply to the bit across a gap.
                val spec = wallOverrides[key]?.takeUnless { bridge && it.type == WallType.EXTERNAL }
                val type = spec?.type ?: auto
                val extra = if (n != null) 0.0 else r.bays.filter { b ->
                    b.side == side && b.span(r).let { (p, q) -> (p + q) / 2 in s0..s1 }
                }.sumOf { it.perimeter - it.width - it.ownEnds.sumOf { e -> e.third } }
                // A virtual wall is no wall at all, so it's always 0 mm.
                // A shared wall fills the gap between the two rooms, so it's at least that thick.
                val gap = nb?.second ?: 0.0
                val thick = if (type == WallType.VIRTUAL) 0.0 else spec?.thickness ?: max(thickness(type), round3(gap))
                all += Wall(key, r, side, n, s0, s1, type, thick, spec != null, gap, extra, auto)
            }
        }
    }

    val corners = mutableListOf<WallCorner>()
    for (r in rooms) {
        for (h in listOf(Side.TOP, Side.BOTTOM)) for (v in listOf(Side.LEFT, Side.RIGHT)) {
            val x = if (v == Side.LEFT) r.x else r.x + r.width
            val y = if (h == Side.TOP) r.y else r.y + r.depth
            val hw = all.firstOrNull { it.room === r && it.side == h && (abs(it.from - x) < EPS || abs(it.to - x) < EPS) }
            val vw = all.firstOrNull { it.room === r && it.side == v && (abs(it.from - y) < EPS || abs(it.to - y) < EPS) }
            // Only outside walls need a corner; a shared wall runs on to the outside wall's face.
            if (hw == null || vw == null || hw.neighbour != null || vw.neighbour != null) continue
            val x0 = if (v == Side.LEFT) x - vw.thickness else x
            val y0 = if (h == Side.TOP) y - hw.thickness else y
            // A square bay flush with this corner, whose end there has its own type (e.g. Party): the
            // corner is part of that end wall, so it takes its type.
            fun ownEndAt(side: Side, along: Double) = r.bays.firstNotNullOfOrNull { b ->
                if (b.side != side) null else {
                    val (a, z) = b.span(r)
                    b.ownEnds.firstOrNull { (atStart, _, _) -> abs((if (atStart) a else z) - along) < EPS }?.second
                }
            }
            corners += WallCorner(
                r,
                doubleArrayOf(x0, y0, x0 + vw.thickness, y0 + hw.thickness),
                ownEndAt(h, x) ?: ownEndAt(v, y) ?: if (hw.type.ordinal >= vw.type.ordinal) hw.type else vw.type,
            )
        }
    }

    // A wall between two rooms is found from both sides; keep the lower room's copy.
    // Between two unallocated spaces there's no wall; a room next to one keeps its wall.
    val walls = all.filter { w ->
        val n = w.neighbour ?: return@filter true
        if (w.room.unallocated && n.unallocated) return@filter false
        val twin = all.any { it.room === n && it.neighbour === w.room && it.key == w.key }
        !twin || rooms.indexOf(w.room) < rooms.indexOf(n)
    }
    // Square bays' ends with their own type: counted as that type, not as the bay's wall.
    val ends = rooms.flatMap { r ->
        r.bays.flatMap { b -> b.ownEnds.map { (s, t, len) -> BayEnd(r, b, s, t, thickness(t), len) } }
    }
    return WallLayout(walls, corners, ends)
}

/**
 * Identifies a wall across moves and saves. Between two rooms it's the same
 * from either side: lower room id first, with that room's side.
 */
fun wallKey(r: Room, side: Side, n: Room?) = when {
    n == null -> "${r.id}:${side.name}"
    r.id < n.id -> "${r.id}-${n.id}:${side.name}"
    else -> "${n.id}-${r.id}:${side.opposite.name}"
}

/** Drops changes to walls of rooms that no longer exist, so a new room can't inherit them. */
fun Plan.pruneWallOverrides() {
    val ids = rooms.map { it.id }.toSet()
    wallOverrides.keys.removeAll { key ->
        key.substringBefore(':').split('-').any { it.toLongOrNull() !in ids }
    }
}

/**
 * Plan extent including wall thickness: x0, y0, x1, y1, or null for an empty
 * plan. With [floor], just that floor's rooms and walls.
 */
fun Plan.bounds(layout: WallLayout = wallLayout(), floor: Floor? = null): DoubleArray? {
    val rs = if (floor == null) rooms else rooms(floor)
    if (rs.isEmpty()) return null
    val rects = rs.map { doubleArrayOf(it.x, it.y, it.x + it.width, it.y + it.depth) } +
        layout.walls.filter { floor == null || it.room.floor == floor }.map { it.rect } +
        layout.corners.filter { floor == null || it.room.floor == floor }.map { it.rect } +
        rs.flatMap { it.bayBoxes(WallType.EXTERNAL.defaultThickness) }
    return doubleArrayOf(rects.minOf { it[0] }, rects.minOf { it[1] }, rects.maxOf { it[2] }, rects.maxOf { it[3] })
}

private fun sideSpan(r: Room, side: Side) =
    if (side.horizontal) r.x to r.x + r.width else r.y to r.y + r.depth

/** Parts of [side] of [r] that no room above it covers (a covered side is that room's wall instead). */
private fun Plan.uncoveredSpans(r: Room, side: Side): List<Pair<Double, Double>> {
    var spans = listOf(sideSpan(r, side))
    for (o in roomsAbove(r)) {
        val covers = when (side) {
            Side.TOP -> o.y <= r.y + EPS && o.y + o.depth > r.y + EPS
            Side.BOTTOM -> o.y + o.depth >= r.y + r.depth - EPS && o.y < r.y + r.depth - EPS
            Side.LEFT -> o.x <= r.x + EPS && o.x + o.width > r.x + EPS
            Side.RIGHT -> o.x + o.width >= r.x + r.width - EPS && o.x < r.x + r.width - EPS
        }
        if (!covers) continue
        val (c0, c1) = sideSpan(o, side)
        spans = spans.flatMap { (a, b) ->
            listOf(a to min(b, c0), max(a, c1) to b).filter { it.second - it.first > MIN_WALL }
        }
    }
    return spans
}

/**
 * Splits the stretch [a]..[b] of [side] of [r] by the room beyond it: for each
 * part, the nearest room within [MAX_SHARED_GAP] (the higher one in the stack on
 * a tie) and the gap to it, or null for none.
 */
private fun Plan.neighbourSpans(r: Room, side: Side, a: Double, b: Double): List<Triple<Double, Double, Pair<Room, Double>?>> {
    class Candidate(val room: Room, val index: Int, val gap: Double, val s0: Double, val s1: Double)

    val cands = mutableListOf<Candidate>()
    for ((i, n) in rooms.withIndex()) {
        if (n === r || n.floor != r.floor) continue
        val gap = when (side) {
            Side.TOP -> if (n.y < r.y - EPS && n.y + n.depth > r.y - MAX_SHARED_GAP) r.y - (n.y + n.depth) else null
            Side.BOTTOM -> (r.y + r.depth).let { e -> if (n.y + n.depth > e + EPS && n.y < e + MAX_SHARED_GAP) n.y - e else null }
            Side.LEFT -> if (n.x < r.x - EPS && n.x + n.width > r.x - MAX_SHARED_GAP) r.x - (n.x + n.width) else null
            Side.RIGHT -> (r.x + r.width).let { e -> if (n.x + n.width > e + EPS && n.x < e + MAX_SHARED_GAP) n.x - e else null }
        } ?: continue
        val (n0, n1) = sideSpan(n, side)
        val s0 = max(a, n0)
        val s1 = min(b, n1)
        if (s1 - s0 > MIN_WALL) cands += Candidate(n, i, max(0.0, gap), s0, s1)
    }

    val cuts = (listOf(a, b) + cands.flatMap { listOf(it.s0, it.s1) }).distinct().sorted()
    val out = mutableListOf<Triple<Double, Double, Pair<Room, Double>?>>()
    for (k in 0 until cuts.size - 1) {
        val mid = (cuts[k] + cuts[k + 1]) / 2
        val n = cands.filter { mid > it.s0 && mid < it.s1 }
            .minWithOrNull(compareBy<Candidate>({ it.gap }, { -it.index }))?.let { it.room to it.gap }
        val last = out.lastOrNull()
        if (last != null && last.third?.first === n?.first && abs(last.second - cuts[k]) < EPS) {
            out[out.size - 1] = Triple(last.first, cuts[k + 1], n)
        } else {
            out += Triple(cuts[k], cuts[k + 1], n)
        }
    }
    return out.filter { it.second - it.first > MIN_WALL }
}

/**
 * True when the stretch [s0]..[s1] of [side] of [r], with no room straight
 * beyond it, is no longer than [MAX_SHARED_GAP] and sits across the gap between
 * two rooms beyond it: one ending at [s0] and one starting at [s1]. That gap
 * holds the wall between those two rooms, so this bit of wall is its end and
 * internal, not an outside wall (e.g. a bedroom's wall across the gap between
 * the bathroom and lobby below it).
 */
private fun Plan.bridgesGap(r: Room, side: Side, s0: Double, s1: Double): Boolean {
    if (s1 - s0 > MAX_SHARED_GAP + EPS) return false
    val beyond = rooms.filter { n ->
        n !== r && n.floor == r.floor && when (side) {
            Side.TOP -> n.y < r.y - EPS && n.y + n.depth > r.y - MAX_SHARED_GAP
            Side.BOTTOM -> (r.y + r.depth).let { e -> n.y + n.depth > e + EPS && n.y < e + MAX_SHARED_GAP }
            Side.LEFT -> n.x < r.x - EPS && n.x + n.width > r.x - MAX_SHARED_GAP
            Side.RIGHT -> (r.x + r.width).let { e -> n.x + n.width > e + EPS && n.x < e + MAX_SHARED_GAP }
        }
    }
    val endsAtStart = beyond.any { abs(sideSpan(it, side).second - s0) < EPS }
    val startsAtEnd = beyond.any { abs(sideSpan(it, side).first - s1) < EPS }
    return endsAtStart && startsAtEnd
}

/** Wall types counted in the heat loss perimeter (HLP); Party walls are counted separately (PWL). */
val HLP_TYPES = setOf(WallType.EXTERNAL, WallType.SHELTERED, WallType.GLAZING)

/**
 * One line of the dimensions table: a building part on one floor. [area] is
 * the rooms' net area, [height] their area-weighted average height, [hlp] the
 * heat loss perimeter (External, Sheltered and Glazing walls) and [pwl] the
 * party wall length, all in metres.
 */
data class DimRow(
    val part: BuildingPart?, val floor: Floor?, val rooms: Int,
    val area: Double, val height: Double, val hlp: Double, val pwl: Double,
)

/** How a dimensions row is shown: a part on one floor, a part's subtotal, or the grand total. */
enum class DimStyle { ROW, SUBTOTAL, TOTAL }

/** Adds rows up; the height is weighted by area. */
fun List<DimRow>.total(part: BuildingPart? = null, floor: Floor? = null): DimRow {
    val area = sumOf { it.area }
    val height = if (area > 0) sumOf { it.area * it.height } / area else 0.0
    return DimRow(part, floor, sumOf { it.rooms }, round3(area), round3(height), round3(sumOf { it.hlp }), round3(sumOf { it.pwl }))
}

/**
 * The dimensions table: one row per building part and floor, in part then
 * floor order. Unallocated space adds no area, but its outside walls are
 * still the building's envelope, so they count towards HLP and PWL for its
 * part and floor. A wall between unallocated space and a room counts for
 * the room.
 */
fun Plan.dimensions(layout: WallLayout = wallLayout()): List<DimRow> {
    val hlp = mutableMapOf<Pair<BuildingPart, Floor>, Double>()
    val pwl = mutableMapOf<Pair<BuildingPart, Floor>, Double>()
    for (w in layout.walls) {
        val owner = w.neighbour?.takeIf { w.room.unallocated && !it.unallocated } ?: w.room
        val key = owner.part to owner.floor
        if (w.type in HLP_TYPES) hlp[key] = (hlp[key] ?: 0.0) + w.length
        if (w.type == WallType.PARTY) pwl[key] = (pwl[key] ?: 0.0) + w.length
    }
    for (e in layout.bayEnds) {
        val key = e.room.part to e.room.floor
        if (e.type in HLP_TYPES) hlp[key] = (hlp[key] ?: 0.0) + e.length
        if (e.type == WallType.PARTY) pwl[key] = (pwl[key] ?: 0.0) + e.length
    }
    // Every part and floor with rooms, or with walls of its own (e.g. only unallocated space).
    val byKey = rooms.groupBy { it.part to it.floor }
    val keys = (allocated.map { it.part to it.floor } + hlp.keys + pwl.keys).toSortedSet(compareBy({ it.first }, { it.second }))
    return keys.map { key ->
        val rs = allocated.filter { (it.part to it.floor) == key }
        val area = rs.sumOf { netArea(it) }
        val volume = rs.sumOf { netVolume(it) }
        val height = when {
            area > 0 -> volume / area
            rs.isNotEmpty() -> rs.map { it.height }.average()
            else -> byKey[key]?.map { it.height }?.average() ?: 0.0
        }
        DimRow(key.first, key.second, rs.count { shows(it) }, round3(area), round3(height), round3(hlp[key] ?: 0.0), round3(pwl[key] ?: 0.0))
    }
}
