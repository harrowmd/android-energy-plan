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

class Plan(
    var name: String = "Untitled plan",
    val rooms: MutableList<Room> = mutableListOf(),
) {
    val totalArea get() = rooms.sumOf { it.area }
    val totalVolume get() = rooms.sumOf { it.volume }

    fun room(id: Long?) = rooms.firstOrNull { it.id == id }
    fun nextId() = (rooms.maxOfOrNull { it.id } ?: 0L) + 1
    fun copy() = Plan(name, rooms.map { it.copy() }.toMutableList())

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
                put("area", round3(r.area))
                put("volume", round3(r.volume))
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

        /** Parses a plan saved by [toJson]. area/volume are derived, so they're ignored on load. */
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
