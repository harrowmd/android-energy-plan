package com.manytwo.energyplan

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
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
    /** Bay windows on its outside walls; they belong to this floor only. Replaced, never changed in place. */
    var bays: List<Bay> = emptyList(),
    /** Doors in its walls; they belong to this floor only. Replaced, never changed in place. */
    var doors: List<Door> = emptyList(),
) {
    /** Floor area: the rectangle plus any bay windows. */
    val area get() = width * depth + bays.sumOf { it.area }
    val volume get() = area * height

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
    /**
     * This plan's default thickness per wall type: the last thickness chosen
     * for that type in the wall dialog. Automatic walls (and new rooms) use it.
     */
    val wallDefaults: MutableMap<WallType, Double> = mutableMapOf(),
) {
    /** Thickness for automatic walls of [type] in this plan. */
    fun thickness(type: WallType) = wallDefaults[type] ?: type.defaultThickness

    /** False for a room completely covered by rooms on top: nothing of it is left, so it isn't reported. */
    fun shows(r: Room) = netArea(r) > 0.0005

    /** Real rooms that can be seen: what room counts and schedules report. */
    val counted get() = rooms.filter { !it.unallocated && shows(it) }

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
        FloorTotal(floor, rs.count { shows(it) }, round3(rs.sumOf { netArea(it) }), round3(rs.sumOf { netVolume(it) }))
    }

    /** (part, rooms, net area, net volume) for each building part in use, Main first. */
    fun partTotals() = allocated.groupBy { it.part }.toSortedMap().map { (part, rs) ->
        PartTotal(part, rs.count { shows(it) }, round3(rs.sumOf { netArea(it) }), round3(rs.sumOf { netVolume(it) }))
    }

    fun room(id: Long?) = rooms.firstOrNull { it.id == id }
    fun nextId() = (rooms.maxOfOrNull { it.id } ?: 0L) + 1
    fun copy() = Plan(name, rooms.map { it.copy() }.toMutableList(), wallOverrides.toMutableMap(), wallDefaults.toMutableMap())

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
        val copies = originals.map { it.copy(id = ids.getValue(it.id), floor = to, bays = emptyList(), doors = emptyList()) }
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

    fun netArea(r: Room) = max(0.0, round3(r.area - cover(r).coveredArea))
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
                if (r.bays.isNotEmpty()) put("bays", JSONArray().apply {
                    for (b in r.bays) put(JSONObject().apply {
                        put("type", b.type.label)
                        put("side", b.side.label)
                        put("offset", round3(b.offset))
                        put("width", round3(b.width))
                        put("depth", round3(b.depth))
                        if (b.type == BayType.ANGLED) put("hinge", round3(b.hinge))
                        put("area", round3(b.area))
                        put("perimeter", round3(b.perimeter))
                    })
                })
                if (r.doors.isNotEmpty()) put("doors", JSONArray().apply {
                    for (d in r.doors) put(JSONObject().apply {
                        put("side", d.side.label)
                        put("offset", round3(d.offset))
                        put("width", round3(d.width))
                        put("hinge", d.hinge.label)
                        put("opens", d.swing.label)
                    })
                })
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
            val dims = dimensions(layout)
            put("dimensions", JSONArray().apply {
                for (r in dims) put(JSONObject().apply {
                    put("buildingPart", r.part!!.label)
                    put("floor", r.floor!!.label)
                    put("rooms", r.rooms)
                    put("area", r.area)
                    put("height", r.height)
                    put("hlp", r.hlp)
                    put("pwl", r.pwl)
                })
            })
            put("dimensionsTotal", dims.total().let { t ->
                JSONObject().apply {
                    put("rooms", t.rooms)
                    put("area", t.area)
                    put("height", t.height)
                    put("hlp", t.hlp)
                    put("pwl", t.pwl)
                }
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
            put("wallDefaults", JSONArray().apply {
                for ((type, t) in wallDefaults.toSortedMap()) put(JSONObject().apply {
                    put("type", type.label)
                    put("thickness", t)
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
                        bays = r.optJSONArray("bays")?.let { arr ->
                            (0 until arr.length()).mapNotNull { k ->
                                val b = arr.getJSONObject(k)
                                val side = Side.entries.firstOrNull { it.label == b.optString("side") } ?: return@mapNotNull null
                                Bay(
                                    BayType.parse(b.optString("type")) ?: BayType.SQUARE, side,
                                    b.getDouble("offset"), b.getDouble("width"), b.getDouble("depth"),
                                )
                            }
                        } ?: emptyList(),
                        doors = r.optJSONArray("doors")?.let { arr ->
                            (0 until arr.length()).mapNotNull { k ->
                                val d = arr.getJSONObject(k)
                                val side = Side.entries.firstOrNull { it.label == d.optString("side") } ?: return@mapNotNull null
                                Door(
                                    side, d.getDouble("offset"), d.optDouble("width", Door.DEFAULT_WIDTH),
                                    DoorHinge.parse(d.optString("hinge")) ?: DoorHinge.LEFT,
                                    DoorSwing.parse(d.optString("opens")) ?: DoorSwing.IN,
                                )
                            }
                        } ?: emptyList(),
                    )
                )
            }
            val overrides = o.optJSONArray("wallOverrides")
            for (i in 0 until (overrides?.length() ?: 0)) {
                val w = overrides!!.getJSONObject(i)
                val type = WallType.parse(w.optString("type")) ?: continue
                plan.wallOverrides[w.getString("key")] = WallSpec(type, w.optDouble("thickness", type.defaultThickness))
            }
            val defaults = o.optJSONArray("wallDefaults")
            for (i in 0 until (defaults?.length() ?: 0)) {
                val w = defaults!!.getJSONObject(i)
                val type = WallType.parse(w.optString("type")) ?: continue
                plan.wallDefaults[type] = w.getDouble("thickness")
            }
            if (defaults == null) {
                // Older plans: take each type's most used thickness among the walls the user changed.
                plan.wallOverrides.values.groupBy { it.type }.forEach { (type, specs) ->
                    plan.wallDefaults[type] = specs.groupingBy { it.thickness }.eachCount().maxBy { it.value }.key
                }
            }
            plan.pruneWallOverrides()
            plan.fitBayHinges()
            return plan
        }
    }
}

data class FloorTotal(val floor: Floor, val rooms: Int, val area: Double, val volume: Double)

data class PartTotal(val part: BuildingPart, val rooms: Int, val area: Double, val volume: Double)

const val DEFAULT_HEIGHT = 2.4

/** Rounds to the nearest millimetre, so snapped/typed values don't pick up float noise. */
fun round3(v: Double) = round(v * 1000) / 1000

/** Tiny float noise either side of zero would print as "-0.00". */
private fun noNegZero(v: Double) = if (kotlin.math.abs(v) < 0.0005) 0.0 else v

fun fmtM(v: Double) = String.format(Locale.UK, "%.2f", noNegZero(v))
fun fmtArea(v: Double) = String.format(Locale.UK, "%.1f", noNegZero(v))
