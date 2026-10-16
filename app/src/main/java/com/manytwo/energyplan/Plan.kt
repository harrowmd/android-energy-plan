package com.manytwo.energyplan

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/** Which part of the building a room is in: the main house, one of up to four extensions, or a conservatory. */
enum class BuildingPart(val label: String) {
    MAIN("Main"), EXT1("Ext1"), EXT2("Ext2"), EXT3("Ext3"), EXT4("Ext4"), CONSERVATORY("Conservatory");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.label.equals(s, true) || it.name.equals(s, true) }
    }
}

/** The storey a room is on. Rooms on different floors never overlap or share walls. */
enum class Floor(val label: String) {
    BASEMENT("Basement"), GROUND("Ground"), FIRST("1st"), SECOND("2nd"), THIRD("3rd"),
    FOURTH("4th"), FIFTH("5th"), SIXTH("6th"), SEVENTH("7th");

    val above get() = entries.getOrNull(ordinal + 1)

    /** "Ground floor", "1st floor"; the basement is just "Basement". */
    val title get() = if (this == BASEMENT) label else "$label floor"

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.label.equals(s, true) || it.name.equals(s, true) }
    }
}

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
    var part: BuildingPart = BuildingPart.MAIN,
    var floor: Floor = Floor.GROUND,
    /**
     * A deleted room whose space is kept: it still shapes the walls exactly as
     * before, but isn't a room - it's left out of every area and volume total.
     */
    var unallocated: Boolean = false,
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
    /** Walls the user has changed, by [wallKey]; every other wall gets its automatic type. */
    val wallOverrides: MutableMap<String, WallSpec> = mutableMapOf(),
) {
    /** Rooms that count: everything except unallocated space. */
    val allocated get() = rooms.filter { !it.unallocated }

    val totalArea get() = allocated.sumOf { netArea(it) }
    val totalVolume get() = allocated.sumOf { netVolume(it) }
    val unallocatedArea get() = rooms.filter { it.unallocated }.sumOf { netArea(it) }

    fun rooms(floor: Floor) = rooms.filter { it.floor == floor }

    /** Floors that have rooms, lowest first. */
    fun floors() = rooms.map { it.floor }.distinct().sorted()

    /** (floor, rooms, net area, net volume) for each floor in use, lowest first. */
    fun floorTotals() = allocated.groupBy { it.floor }.toSortedMap().map { (floor, rs) ->
        FloorTotal(floor, rs.size, round3(rs.sumOf { netArea(it) }), round3(rs.sumOf { netVolume(it) }))
    }

    /** (part, rooms, net area, net volume) for each building part in use, Main first. */
    fun partTotals() = allocated.groupBy { it.part }.toSortedMap().map { (part, rs) ->
        PartTotal(part, rs.size, round3(rs.sumOf { netArea(it) }), round3(rs.sumOf { netVolume(it) }))
    }

    fun room(id: Long?) = rooms.firstOrNull { it.id == id }
    fun nextId() = (rooms.maxOfOrNull { it.id } ?: 0L) + 1
    fun copy() = Plan(name, rooms.map { it.copy() }.toMutableList(), wallOverrides.toMutableMap())

    private fun overlaps(a: Room, b: Room) =
        a.floor == b.floor && a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.depth && a.y + a.depth > b.y

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

    /**
     * Copies every room of [part] on [from] to floor [to] (the one above, or
     * the basement), with new ids and the same layout, and returns the copies.
     * Wall changes between the copied rooms (or on their outside walls) are
     * copied too.
     */
    fun copyPart(part: BuildingPart, from: Floor, to: Floor): List<Room> {
        val originals = rooms.filter { it.part == part && it.floor == from }
        var next = nextId()
        val ids = originals.associate { it.id to next++ }
        val copies = originals.map { it.copy(id = ids.getValue(it.id), floor = to) }
        rooms += copies
        for ((key, spec) in wallOverrides.toMap()) {
            val (pair, side) = key.split(':')
            val newIds = pair.split('-').map { ids[it.toLongOrNull()] }
            if (newIds.any { it == null }) continue
            wallOverrides["${newIds.joinToString("-")}:$side"] = spec
        }
        return copies
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
        pruneWallOverrides()
        val layout = wallLayout()
        val roomArray = JSONArray()
        for (r in rooms) {
            roomArray.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("floor", r.floor.label)
                put("buildingPart", r.part.label)
                put("unallocated", r.unallocated)
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
            put("formatVersion", 2)
            put("name", name)
            put("units", "m")
            put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK).format(Date()))
            put("totalArea", round3(totalArea))
            put("totalVolume", round3(totalVolume))
            put("unallocatedArea", round3(unallocatedArea))
            put("rooms", roomArray)
            put("floorTotals", JSONArray().apply {
                for (t in floorTotals()) put(JSONObject().apply {
                    put("floor", t.floor.label)
                    put("rooms", t.rooms)
                    put("area", t.area)
                    put("volume", t.volume)
                })
            })
            put("partTotals", JSONArray().apply {
                for (t in partTotals()) put(JSONObject().apply {
                    put("buildingPart", t.part.label)
                    put("rooms", t.rooms)
                    put("area", t.area)
                    put("volume", t.volume)
                })
            })
            put("walls", JSONArray().apply { for (w in layout.walls) put(wallJson(w)) })
            put("wallTotals", JSONArray().apply {
                for ((type, thickness, length) in layout.totals()) put(JSONObject().apply {
                    put("type", type.label)
                    put("thickness", thickness)
                    put("length", length)
                })
            })
            put("wallOverrides", JSONArray().apply {
                for ((key, spec) in wallOverrides.toSortedMap()) put(JSONObject().apply {
                    put("key", key)
                    put("type", spec.type.label)
                    put("thickness", spec.thickness)
                })
            })
        }.toString(2)
    }

    private fun wallJson(w: Wall) = JSONObject().apply {
        val (x1, y1, x2, y2) = w.ends.toList()
        put("floor", w.room.floor.label)
        put("roomId", w.room.id)
        put("room", w.room.name)
        put("side", w.side.label)
        put("neighbourId", w.neighbour?.id ?: JSONObject.NULL)
        put("neighbour", w.neighbour?.name ?: JSONObject.NULL)
        put("type", w.type.label)
        put("thickness", w.thickness)
        put("length", w.length)
        put("x1", round3(x1))
        put("y1", round3(y1))
        put("x2", round3(x2))
        put("y2", round3(y2))
    }

    companion object {
        const val FORMAT = "m21energyplan"

        /** Parses a plan saved by [toJson]. Areas, volumes and walls are derived, so they're ignored on load; only wall changes are read. */
        fun fromJson(text: String): Plan {
            val o = JSONObject(text)
            val arr = o.getJSONArray("rooms")
            val plan = Plan(o.optString("name", "Untitled plan"))
            val ids = mutableSetOf<Long>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                // Ids key the wall changes; version 1 files have none, so number them in order.
                val id = r.optLong("id", i + 1L).takeIf { it > 0 && it !in ids } ?: ((ids.maxOrNull() ?: 0L) + 1)
                ids += id
                plan.rooms.add(
                    Room(
                        id = id,
                        name = r.optString("name", "Room ${i + 1}"),
                        x = r.getDouble("x"),
                        y = r.getDouble("y"),
                        width = r.getDouble("width"),
                        depth = r.getDouble("depth"),
                        height = r.optDouble("height", DEFAULT_HEIGHT),
                        part = BuildingPart.parse(r.optString("buildingPart")) ?: BuildingPart.MAIN,
                        floor = Floor.parse(r.optString("floor")) ?: Floor.GROUND,
                        unallocated = r.optBoolean("unallocated", false),
                    )
                )
            }
            val overrides = o.optJSONArray("wallOverrides")
            for (i in 0 until (overrides?.length() ?: 0)) {
                val w = overrides!!.getJSONObject(i)
                val type = WallType.parse(w.optString("type")) ?: continue
                plan.wallOverrides[w.getString("key")] = WallSpec(type, w.optDouble("thickness", type.defaultThickness))
            }
            plan.pruneWallOverrides()
            return plan
        }
    }
}

data class FloorTotal(val floor: Floor, val rooms: Int, val area: Double, val volume: Double)

data class PartTotal(val part: BuildingPart, val rooms: Int, val area: Double, val volume: Double)

const val DEFAULT_HEIGHT = 2.4

/** Rounds to the nearest millimetre, so snapped/typed values don't pick up float noise. */
fun round3(v: Double) = round(v * 1000) / 1000

fun fmtM(v: Double) = String.format(Locale.UK, "%.2f", v)
fun fmtArea(v: Double) = String.format(Locale.UK, "%.1f", v)
