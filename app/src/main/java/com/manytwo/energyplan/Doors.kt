package com.manytwo.energyplan

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which side of the opening a door is hung on, as seen from inside its room facing the wall. */
enum class DoorHinge(val label: String) {
    LEFT("Left"), RIGHT("Right");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.label.equals(s, true) || it.name.equals(s, true) }
    }
}

/** Whether a door opens into its room or out of it (into the next room, or outside). */
enum class DoorSwing(val label: String) {
    IN("In"), OUT("Out");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.label.equals(s, true) || it.name.equals(s, true) }
    }
}

/**
 * A door in one [side] of a room: [width] along the wall, starting [offset]
 * metres from the start of that side (its left or top end). It's drawn as
 * an opening in the wall with a [LEAF]-thick line of wall along its middle,
 * the door leaf standing open at [OPEN_ANGLE] and a thin arc for its swing.
 * Doors don't change areas or wall lengths.
 */
data class Door(
    val side: Side,
    val offset: Double,
    val width: Double = DEFAULT_WIDTH,
    val hinge: DoorHinge = DoorHinge.LEFT,
    val swing: DoorSwing = DoorSwing.IN,
) {
    /** Where the door is along the side, start and end in plan coordinates. */
    fun span(r: Room): Pair<Double, Double> {
        val base = if (side.horizontal) r.x else r.y
        return base + offset to base + offset + width
    }

    fun describe() = "${fmtM(width)} m door on the ${side.label} wall (hinged ${hinge.label.lowercase()}, opens ${swing.label.lowercase()})"

    companion object {
        const val DEFAULT_WIDTH = 1.0
        /** Thickness of the door leaf, and of the line of wall drawn across the opening. */
        const val LEAF = 0.04
        /** How far open doors are drawn, in degrees. */
        const val OPEN_ANGLE = 45.0
    }
}

/**
 * How a door is drawn, in plan metres: the [opening] cut out of the wall
 * (x0, y0, x1, y1), the [sill] - a line of wall along the middle of the
 * opening, the same way round - the open leaf from its [hinge] to its [tip],
 * and the [arc] its free edge sweeps from the closed position to the tip.
 * [wall] is the wall it's in, for the colour; [swept] bounds the leaf and arc.
 */
class DoorShape(
    val wall: Wall, val opening: DoubleArray, val sill: DoubleArray,
    val hinge: DoubleArray, val tip: DoubleArray, val arc: List<DoubleArray>, val swept: DoubleArray,
)

/** Where [door] of [r] is drawn, from the wall it sits in; null when there's no wall there (e.g. between unallocated spaces). */
fun WallLayout.doorShape(r: Room, door: Door): DoorShape? {
    val side = door.side
    val (a, z) = door.span(r)
    val mid = (a + z) / 2
    val wall = walls.firstOrNull { w ->
        ((w.room === r && w.side == side) || (w.neighbour === r && w.side == side.opposite)) && mid >= w.from - 1e-6 && mid <= w.to + 1e-6
    } ?: return null
    val rc = wall.rect
    val (lo, hi) = if (side.horizontal) rc[1] to rc[3] else rc[0] to rc[2]
    // Away from the room: +1 for its bottom and right walls.
    val out = if (side == Side.BOTTOM || side == Side.RIGHT) 1 else -1
    val (inner, outer) = if (out > 0) lo to hi else hi to lo
    val dir = if (door.swing == DoorSwing.IN) -out else out
    val face = if (door.swing == DoorSwing.IN) inner else outer
    // Facing the wall from inside the room, its left end is the low end for the top and right walls.
    val leftIsLow = side == Side.TOP || side == Side.RIGHT
    val hingeLow = (door.hinge == DoorHinge.LEFT) == leftIsLow
    val hu = if (hingeLow) a else z
    val toFree = if (hingeLow) 1 else -1
    val w = door.width

    // u runs along the wall, v across it; back to plan x, y.
    fun pt(u: Double, v: Double) = if (side.horizontal) doubleArrayOf(u, v) else doubleArrayOf(v, u)
    fun box(u0: Double, v0: Double, u1: Double, v1: Double): DoubleArray {
        val p = pt(min(u0, u1), min(v0, v1))
        val q = pt(max(u0, u1), max(v0, v1))
        return doubleArrayOf(p[0], p[1], q[0], q[1])
    }

    val opening = box(a, lo, z, hi)
    val centre = (lo + hi) / 2
    val sill = box(a, centre - Door.LEAF / 2, z, centre + Door.LEAF / 2)
    // Angles from the closed position (along the wall towards the far jamb) out to the open leaf.
    fun swing(t: Double) = pt(hu + toFree * w * cos(t), face + dir * w * sin(t))
    val open = Math.toRadians(Door.OPEN_ANGLE)
    val arc = (0..ARC_STEPS).map { i -> swing(open * i / ARC_STEPS) }
    val tip = swing(open)
    val reach = w * sin(open)
    val swept = box(hu, face, hu + toFree * w, face + dir * reach)
    return DoorShape(wall, opening, sill, pt(hu, face), tip, arc, swept)
}

/** Keeps a door of [width] centred near [centre] on a side running from [lo] to [hi]; null if it's too wide. */
fun fitDoor(centre: Double, width: Double, lo: Double, hi: Double): Double? = fitBay(centre, width, lo, hi)

private const val ARC_STEPS = 24
