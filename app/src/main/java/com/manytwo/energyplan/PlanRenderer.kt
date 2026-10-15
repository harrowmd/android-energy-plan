package com.manytwo.energyplan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Draws plans onto a Canvas. Shared by the on-screen [PlanView] and the JPG
 * export, so both look the same. [d] is pixels per dp - the export uses a
 * bigger value than the screen so text stays readable on a large image.
 */
class PlanRenderer(private val d: Float) {

    private val gridMinor = Paint().apply { color = 0xFFEDF0F2.toInt(); strokeWidth = 1f }
    private val gridMajor = Paint().apply { color = 0xFFCFD6DB.toInt(); strokeWidth = max(1f, d * 0.75f) }
    private val fill = Paint().apply { color = 0xFFE8F5E9.toInt() }
    private val fillSelected = Paint().apply { color = 0xFFFFF3D6.toInt() }
    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263238.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val wallSelected = Paint(wall).apply { color = 0xFFE67E00.toInt(); strokeWidth = 3.5f * d }
    private val draftFill = Paint().apply { color = 0x332E7D32 }
    private val draftLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt(); textSize = 14f * d; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val infoText = TextPaint(nameText).apply {
        color = 0xFF37474F.toInt(); textSize = 12f * d; typeface = Typeface.DEFAULT
    }
    private val dimText = TextPaint(infoText).apply { color = 0xFF546E7A.toInt(); textSize = 11f * d }
    private val draftText = TextPaint(nameText).apply { color = 0xFF1B5E20.toInt(); textSize = 13f * d }

    /** Draws the grid and returns the minor grid step in metres. */
    fun drawGrid(c: Canvas, scale: Float, offX: Float, offY: Float, w: Int, h: Int): Double {
        val (minor, major) = GRID_STEPS.firstOrNull { it.first * scale >= 10f * d } ?: GRID_STEPS.last()
        drawLines(c, minor, major, scale, offX, w, h, vertical = true)
        drawLines(c, minor, major, scale, offY, w, h, vertical = false)
        return minor
    }

    private fun drawLines(
        c: Canvas, minor: Double, major: Double, scale: Float, off: Float,
        w: Int, h: Int, vertical: Boolean,
    ) {
        val extent = if (vertical) w else h
        val first = floor(-off / scale / minor).toLong()
        val last = ceil((extent - off) / scale / minor).toLong()
        for (i in first..last) {
            val m = i * minor
            val isMajor = abs(m / major - round(m / major)) < 1e-6
            val p = off + (m * scale).toFloat()
            val paint = if (isMajor) gridMajor else gridMinor
            if (vertical) c.drawLine(p, 0f, p, h.toFloat(), paint)
            else c.drawLine(0f, p, w.toFloat(), p, paint)
        }
    }

    fun drawRoom(c: Canvas, r: Room, scale: Float, offX: Float, offY: Float, selected: Boolean) {
        val rect = RectF(
            offX + (r.x * scale).toFloat(), offY + (r.y * scale).toFloat(),
            offX + ((r.x + r.width) * scale).toFloat(), offY + ((r.y + r.depth) * scale).toFloat(),
        )
        c.drawRect(rect, if (selected) fillSelected else fill)
        c.drawRect(rect, if (selected) wallSelected else wall)
        drawEdgeDimensions(c, r, rect)
        drawCentreLabel(c, r, rect)
    }

    /** Width along the inside of the top wall, depth rotated along the left wall. */
    private fun drawEdgeDimensions(c: Canvas, r: Room, rect: RectF) {
        val pad = 4f * d
        val size = dimText.textSize
        if (rect.height() < size * 5) return

        val wLabel = "${fmtM(r.width)} m"
        if (dimText.measureText(wLabel) + 2 * pad < rect.width()) {
            c.drawText(wLabel, rect.centerX(), rect.top + pad + size, dimText)
        }
        val dLabel = "${fmtM(r.depth)} m"
        if (dimText.measureText(dLabel) + 2 * pad < rect.height() && rect.width() > size * 5) {
            c.save()
            c.rotate(-90f, rect.left + pad + size, rect.centerY())
            c.drawText(dLabel, rect.left + pad + size, rect.centerY(), dimText)
            c.restore()
        }
    }

    /** Name, area and height in the middle, shrunk or trimmed to fit the room. */
    private fun drawCentreLabel(c: Canvas, r: Room, rect: RectF) {
        val pad = 4f * d
        // Keep clear of the edge dimensions: the depth label down the left, the width label along the top.
        val left = rect.left + pad + dimText.textSize * 1.4f
        val top = rect.top + pad + dimText.textSize * 1.4f
        val availW = rect.right - pad - left
        val availH = rect.bottom - pad - top
        val cx = (left + rect.right - pad) / 2
        val cy = (top + rect.bottom - pad) / 2
        if (availW <= 0 || availH <= 0) return

        val all = listOf(
            r.name to nameText,
            "${fmtArea(r.area)} m²" to infoText,
            "h ${fmtM(r.height)} m" to infoText,
        )
        for (n in all.size downTo 1) {
            val lines = all.take(n)
            val needW = lines.maxOf { (t, p) -> p.measureText(t) }
            val needH = lines.sumOf { (_, p) -> (p.textSize * 1.2f).toDouble() }.toFloat()
            val s = min(1f, min(availW / needW, availH / needH))
            if (s < 0.6f) continue

            var y = cy - needH * s / 2
            for ((text, paint) in lines) {
                val p = TextPaint(paint).apply { textSize = paint.textSize * s }
                y += p.textSize * 1.2f
                c.drawText(text, cx, y - p.textSize * 0.25f, p)
            }
            return
        }
    }

    fun drawDraft(c: Canvas, rect: RectF, widthM: Double, depthM: Double) {
        c.drawRect(rect, draftFill)
        c.drawRect(rect, draftLine)
        val label = "${fmtM(widthM)} × ${fmtM(depthM)} m"
        c.drawText(label, rect.centerX(), rect.centerY() + draftText.textSize / 3, draftText)
    }

    companion object {
        /** (minor, major) grid steps in metres, smallest first. */
        private val GRID_STEPS = listOf(0.1 to 1.0, 0.5 to 1.0, 1.0 to 5.0, 5.0 to 10.0, 10.0 to 50.0)

        private const val EXPORT_D = 3f

        /**
         * Renders the whole plan, with a title, a 1 m scale bar and a room
         * schedule (sizes, area, volume), onto a white bitmap for JPG export.
         */
        fun renderExport(plan: Plan): Bitmap {
            val d = EXPORT_D
            val r = PlanRenderer(d)
            val margin = 40f * d
            val headerH = 64f * d
            val rowH = 22f * d

            val rooms = plan.rooms
            val minX = rooms.minOfOrNull { it.x } ?: 0.0
            val minY = rooms.minOfOrNull { it.y } ?: 0.0
            val bw = max((rooms.maxOfOrNull { it.x + it.width } ?: 4.0) - minX, 1.0)
            val bh = max((rooms.maxOfOrNull { it.y + it.depth } ?: 3.0) - minY, 1.0)
            val scale = (2000.0 / max(bw, bh)).toFloat()
            val planW = (bw * scale).toFloat()
            val planH = (bh * scale).toFloat()

            val tableTop = headerH + planH + 2 * margin
            val tableH = rowH * (rooms.size + 2) + 12f * d
            val width = max(planW + 2 * margin, 1800f).toInt()
            val height = (tableTop + tableH + margin).toInt()

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)

            val offX = (width - planW) / 2 - (minX * scale).toFloat()
            val offY = headerH + margin - (minY * scale).toFloat()

            // Grid only behind the plan area, not the header or table.
            c.save()
            c.clipRect(0f, headerH, width.toFloat(), tableTop)
            r.drawGrid(c, scale, offX, offY, width, height)
            c.restore()
            for (room in rooms) r.drawRoom(c, room, scale, offX, offY, false)

            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A1A.toInt(); textSize = 24f * d; typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextPaint(title).apply { color = 0xFF546E7A.toInt(); textSize = 13f * d; typeface = Typeface.DEFAULT }
            c.drawText(plan.name, margin, 32f * d, title)
            val date = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date())
            c.drawText(
                "$date  ·  ${rooms.size} rooms  ·  ${fmtArea(plan.totalArea)} m²  ·  ${fmtArea(plan.totalVolume)} m³",
                margin, 52f * d, sub,
            )

            // 1 m scale bar, bottom-left of the plan area.
            val barY = tableTop - 14f * d
            val bar = Paint().apply { color = 0xFF263238.toInt(); strokeWidth = 2f * d }
            c.drawLine(margin, barY, margin + scale, barY, bar)
            c.drawLine(margin, barY - 4 * d, margin, barY + 4 * d, bar)
            c.drawLine(margin + scale, barY - 4 * d, margin + scale, barY + 4 * d, bar)
            c.drawText("1 m", margin + scale + 6 * d, barY + 4 * d, sub)

            drawSchedule(c, plan, margin, tableTop, width - 2 * margin, rowH, d)
            return bmp
        }

        private fun drawSchedule(c: Canvas, plan: Plan, left: Float, top: Float, w: Float, rowH: Float, d: Float) {
            val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A1A.toInt(); textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
            }
            val body = TextPaint(head).apply { color = 0xFF263238.toInt(); typeface = Typeface.DEFAULT }
            val line = Paint().apply { color = 0xFF90A4AE.toInt(); strokeWidth = d }

            // Column right-edges as fractions of the table width (first column is left-aligned).
            val cols = listOf(0.0f, 0.46f, 0.58f, 0.70f, 0.85f, 1.0f)
            fun row(y: Float, cells: List<String>, p: TextPaint) {
                cells.forEachIndexed { i, text ->
                    if (i == 0) {
                        p.textAlign = Paint.Align.LEFT
                        c.drawText(text, left, y, p)
                    } else {
                        p.textAlign = Paint.Align.RIGHT
                        c.drawText(text, left + w * cols[i], y, p)
                    }
                }
            }

            var y = top + rowH * 0.7f
            row(y, listOf("Room", "Width x", "Depth y", "Height z", "Area m²", "Volume m³"), head)
            c.drawLine(left, y + rowH * 0.3f, left + w, y + rowH * 0.3f, line)
            for (r in plan.rooms) {
                y += rowH
                row(y, listOf(r.name, fmtM(r.width), fmtM(r.depth), fmtM(r.height), fmtM(r.area), fmtM(r.volume)), body)
            }
            c.drawLine(left, y + rowH * 0.3f, left + w, y + rowH * 0.3f, line)
            y += rowH
            row(y, listOf("Total", "", "", "", fmtM(plan.totalArea), fmtM(plan.totalVolume)), head)
        }
    }
}
