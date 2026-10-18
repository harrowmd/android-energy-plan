package com.manytwo.energyplan

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class BayType(val label: String) {
    ANGLED("Angled"), SQUARE("Square"), BOW("Bow");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.label.equals(s, true) || it.name.equals(s, true) }
    }
}

/**
 * A bay window on one outside [side] of a room: [width] along the wall,
 * starting [offset] metres from the start of that side (its left or top
 * end), and [depth] metres out from the room's face. Angled bays have 45°
 * sides (steeper if the bay is too deep for that); bow bays are a circular
 * arc. The bay adds its [area] to the room, and its outline replaces
 * [width] of the wall, so the wall gets [perimeter] - [width] longer.
 */
data class Bay(val type: BayType, val side: Side, val offset: Double, val width: Double, val depth: Double) {

    /** How far in from each end the angled bay's front starts. */
    private val inset get() = min(depth, width / 2)

    /** Radius of the bow's arc, through both ends and the point [depth] out at the middle. */
    private val radius get() = (width * width / 4 + depth * depth) / (2 * depth)

    /** Angle the bow's arc spans; past a semicircle when the bow is deeper than half its width. */
    private val arcAngle: Double
        get() {
            val half = asin((width / 2 / radius).coerceIn(-1.0, 1.0))
            return if (depth <= width / 2) 2 * half else 2 * PI - 2 * half
        }

    val area: Double
        get() = when (type) {
            BayType.SQUARE -> width * depth
            BayType.ANGLED -> (2 * width - 2 * inset) / 2 * depth
            BayType.BOW -> radius * radius / 2 * (arcAngle - sin(arcAngle))
        }

    /** Length of the bay's outline, from one end on the room's face round to the other. */
    val perimeter: Double
        get() = when (type) {
            BayType.SQUARE -> width + 2 * depth
            BayType.ANGLED -> 2 * hypot(inset, depth) + width - 2 * inset
            BayType.BOW -> radius * arcAngle
        }

    /** The outline in plan coordinates (metres), from one end on [r]'s face out and back to the other. */
    fun outline(r: Room): List<DoubleArray> {
        val local: List<DoubleArray> = when (type) {
            BayType.SQUARE -> listOf(pt(0.0, 0.0), pt(0.0, depth), pt(width, depth), pt(width, 0.0))
            BayType.ANGLED -> listOf(pt(0.0, 0.0), pt(inset, depth), pt(width - inset, depth), pt(width, 0.0))
            BayType.BOW -> {
                // Centre on the bay's middle line, (radius - depth) back from the face.
                val cv = depth - radius
                val start = PI / 2 + arcAngle / 2
                (0..BOW_STEPS).map { i ->
                    val a = start - arcAngle * i / BOW_STEPS
                    pt(width / 2 + radius * cos(a), cv + radius * sin(a))
                }
            }
        }
        return local.map { (u, v) -> toPlan(r, offset + u, v) }
    }

    /** Where the bay meets the room's face, along the side: start and end in plan coordinates. */
    fun span(r: Room): Pair<Double, Double> {
        val base = if (side.horizontal) r.x else r.y
        return base + offset to base + offset + width
    }

    private fun toPlan(r: Room, u: Double, v: Double): DoubleArray = when (side) {
        Side.TOP -> doubleArrayOf(r.x + u, r.y - v)
        Side.BOTTOM -> doubleArrayOf(r.x + u, r.y + r.depth + v)
        Side.LEFT -> doubleArrayOf(r.x - v, r.y + u)
        Side.RIGHT -> doubleArrayOf(r.x + r.width + v, r.y + u)
    }

    fun describe() = "${type.label} bay ${fmtM(width)} × ${fmtM(depth)} m on the ${side.label} wall (+${fmtM(area)} m²)"

    companion object {
        private const val BOW_STEPS = 24
        private fun pt(u: Double, v: Double) = doubleArrayOf(u, v)
    }
}

/** Plan-coordinate bounding box of [r]'s bays, grown by [margin]: x0, y0, x1, y1, one per bay. */
fun Room.bayBoxes(margin: Double): List<DoubleArray> = bays.map { b ->
    val pts = b.outline(this)
    doubleArrayOf(
        pts.minOf { it[0] } - margin, pts.minOf { it[1] } - margin,
        pts.maxOf { it[0] } + margin, pts.maxOf { it[1] } + margin,
    )
}

/** True when plan point ([x], [y]) is inside the closed [polygon]. */
fun insidePolygon(polygon: List<DoubleArray>, x: Double, y: Double): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (xi, yi) = polygon[i]
        val (xj, yj) = polygon[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private operator fun DoubleArray.component1() = this[0]
private operator fun DoubleArray.component2() = this[1]

/** Keeps a bay of [width] centred near [centre] within [lo]..[hi]; null if it can't fit. */
fun fitBay(centre: Double, width: Double, lo: Double, hi: Double): Double? {
    if (width > hi - lo + 1e-9) return null
    return max(lo, min(hi - width, centre - width / 2))
}
