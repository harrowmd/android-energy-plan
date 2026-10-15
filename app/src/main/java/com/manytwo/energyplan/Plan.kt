package com.manytwo.energyplan

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/**
 * One rectangular room. All sizes are in metres. x/y is the top-left corner
 * on the plan; width runs along x, depth along y, and height is the
 * ceiling height (z) used for volume.
 */
data class Room(
    val id: Long,
    var name: String,
    var x: Double,
    var y: Double,
    var width: Double,
    var depth: Double,
    var height: Double,
) {
    val area get() = width * depth
    val volume get() = width * depth * height

    fun contains(px: Double, py: Double) =
        px >= x && px <= x + width && py >= y && py <= y + depth
}

/**
 * [rooms] is in stacking order: later rooms sit on top of earlier ones.
 * Where rooms overlap, the overlap belongs to the room on top, so a room's
 * net area is its own rectangle minus whatever rooms above it cover.
 */
class Plan(
    var name: String = "Untitled plan",
    val rooms: MutableList<Room> = mutableListOf(),
) {
    val totalArea get() = rooms.sumOf { netArea(it) }
    val totalVolume get() = rooms.sumOf { netVolume(it) }

    fun room(id: Long?) = rooms.firstOrNull { it.id == id }
    fun nextId() = (rooms.maxOfOrNull { it.id } ?: 0L) + 1
    fun copy() = Plan(name, rooms.map { it.copy() }.toMutableList())

    private fun overlaps(a: Room, b: Room) =
        a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.depth && a.y + a.depth > b.y

    /** Rooms stacked above [r] that actually overlap it. */
    fun roomsAbove(r: Room): List<Room> {
        val i = rooms.indexOf(r)
        if (i < 0) return emptyList()
        return rooms.subList(i + 1, rooms.size).filter { overlaps(it, r) }
    }

    /** Rooms stacked below [r] that actually overlap it, nearest first. */
    private fun roomsBelow(r: Room): List<Room> {
        val i = rooms.indexOf(r)
        if (i <= 0) return emptyList()
        return rooms.subList(0, i).filter { overlaps(it, r) }.reversed()
    }

    fun canBringForward(r: Room) = roomsAbove(r).isNotEmpty()
    fun canSendBackward(r: Room) = roomsBelow(r).isNotEmpty()

    fun netArea(r: Room) = round3(r.area - cover(r).coveredArea)
    fun netVolume(r: Room) = netArea(r) * r.height

    /** Uncovered rectangles of [r] as (x0, y0, x1, y1), biggest first; empty if it's hidden completely. */
    fun visibleRects(r: Room): List<DoubleArray> = cover(r).free

    /**
     * One step up past the nearest overlapping room above. Stepping past a
     * room it doesn't touch would change nothing visible, so those are skipped.
     */
    fun bringForward(r: Room) {
        val target = roomsAbove(r).firstOrNull() ?: return
        rooms.remove(r)
        rooms.add(rooms.indexOf(target) + 1, r)
    }

    /** One step down past the nearest overlapping room below. */
    fun sendBackward(r: Room) {
        val target = roomsBelow(r).firstOrNull() ?: return
        rooms.remove(r)
        rooms.add(rooms.indexOf(target), r)
    }

    fun sendToBack(r: Room) {
        if (rooms.remove(r)) rooms.add(0, r)
    }

    fun bringToFront(r: Room) {
        if (rooms.remove(r)) rooms.add(r)
    }

    private class Cover(val coveredArea: Double, val free: List<DoubleArray>)

    /**
     * Splits [r] into a grid along every edge of the rooms above it; each
     * cell is then either wholly covered or wholly free. That gives the exact
     * covered area (no double-counting where the covering rooms overlap each
     * other) and, by brute force over the few cells, every free block.
     */
    private fun cover(r: Room): Cover {
        val above = roomsAbove(r)
        val x1 = r.x + r.width
        val y1 = r.y + r.depth
        if (above.isEmpty()) return Cover(0.0, listOf(doubleArrayOf(r.x, r.y, x1, y1)))

        val xs = (listOf(r.x, x1) + above.flatMap { listOf(it.x, it.x + it.width) })
            .map { it.coerceIn(r.x, x1) }.distinct().sorted()
        val ys = (listOf(r.y, y1) + above.flatMap { listOf(it.y, it.y + it.depth) })
            .map { it.coerceIn(r.y, y1) }.distinct().sorted()
        val nx = xs.size - 1
        val ny = ys.size - 1
        val covered = Array(nx) { i ->
            BooleanArray(ny) { j ->
                val cx = (xs[i] + xs[i + 1]) / 2
                val cy = (ys[j] + ys[j + 1]) / 2
                above.any { it.contains(cx, cy) }
            }
        }

        var area = 0.0
        for (i in 0 until nx) for (j in 0 until ny) {
            if (covered[i][j]) area += (xs[i + 1] - xs[i]) * (ys[j + 1] - ys[j])
        }

        val free = mutableListOf<DoubleArray>()
        for (i0 in 0 until nx) for (i1 in i0 until nx) for (j0 in 0 until ny) for (j1 in j0 until ny) {
            if ((i0..i1).all { i -> (j0..j1).none { j -> covered[i][j] } }) {
                free += doubleArrayOf(xs[i0], ys[j0], xs[i1 + 1], ys[j1 + 1])
            }
        }
        free.sortByDescending { (it[2] - it[0]) * (it[3] - it[1]) }
        return Cover(area, free)
    }

    fun toJson(): String {
        val roomArray = JSONArray()
        for (r in rooms) {
            roomArray.put(JSONObject().apply {
                put("name", r.name)
                put("x", r.x)
                put("y", r.y)
                put("width", r.width)
                put("depth", r.depth)
                put("height", r.height)
                put("grossArea", round3(r.area))
                put("area", round3(netArea(r)))
                put("volume", round3(netVolume(r)))
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("formatVersion", 1)
            put("name", name)
            put("units", "m")
            put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK).format(Date()))
            put("totalArea", round3(totalArea))
            put("totalVolume", round3(totalVolume))
            put("rooms", roomArray)
        }.toString(2)
    }

    companion object {
        const val FORMAT = "m21energyplan"

        /** Parses a plan saved by [toJson]. Areas and volumes are derived, so they're ignored on load. */
        fun fromJson(text: String): Plan {
            val o = JSONObject(text)
            val arr = o.getJSONArray("rooms")
            val plan = Plan(o.optString("name", "Untitled plan"))
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                plan.rooms.add(
                    Room(
                        id = i + 1L,
                        name = r.optString("name", "Room ${i + 1}"),
                        x = r.getDouble("x"),
                        y = r.getDouble("y"),
                        width = r.getDouble("width"),
                        depth = r.getDouble("depth"),
                        height = r.optDouble("height", DEFAULT_HEIGHT),
                    )
                )
            }
            return plan
        }
    }
}

const val DEFAULT_HEIGHT = 2.4

/** Rounds to the nearest millimetre, so snapped/typed values don't pick up float noise. */
fun round3(v: Double) = round(v * 1000) / 1000

fun fmtM(v: Double) = String.format(Locale.UK, "%.2f", v)
fun fmtArea(v: Double) = String.format(Locale.UK, "%.1f", v)
