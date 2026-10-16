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
    /** The room's inner face; the walls themselves are drawn outside it by [drawWalls]. */
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263238.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val selectedLine = Paint(outline).apply { color = 0xFFE67E00.toInt(); strokeWidth = 3.5f * d }
    private val wallFill = Paint()
    private val wallHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
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

    private fun screenRect(r: Room, scale: Float, offX: Float, offY: Float) = RectF(
        offX + (r.x * scale).toFloat(), offY + (r.y * scale).toFloat(),
        offX + ((r.x + r.width) * scale).toFloat(), offY + ((r.y + r.depth) * scale).toFloat(),
    )

    /**
     * Draws [r] in its place in the stacking order. Labels go in the part of
     * the room that rooms above it don't cover, so they stay readable.
     */
    fun drawRoom(c: Canvas, plan: Plan, layout: WallLayout, r: Room, scale: Float, offX: Float, offY: Float, selected: Boolean) {
        val full = screenRect(r, scale, offX, offY)
        c.drawRect(full, if (selected) fillSelected else fill)
        c.drawRect(full, outline)

        // Labels keep clear of shared walls, which are drawn half inside the room.
        fun inset(side: Side) = (layout.intrusion(r, side) * scale).toFloat()
        val rect = RectF(full.left + inset(Side.LEFT), full.top + inset(Side.TOP), full.right - inset(Side.RIGHT), full.bottom - inset(Side.BOTTOM))

        val covers = plan.roomsAbove(r).map { screenRect(it, scale, offX, offY) }
        val visible = plan.visibleRects(r).mapNotNull {
            RectF(offX + (it[0] * scale).toFloat(), offY + (it[1] * scale).toFloat(),
                offX + (it[2] * scale).toFloat(), offY + (it[3] * scale).toFloat())
                .takeIf { b -> b.intersect(rect) }
        }
        if (visible.isEmpty()) return
        val sides = drawEdgeDimensions(c, r, rect, covers)
        drawCentreLabel(c, plan, r, rect, visible, sides)
    }

    /** The orange outline is drawn last, so the selected room shows even if it's underneath others. */
    fun drawSelection(c: Canvas, r: Room, scale: Float, offX: Float, offY: Float) {
        c.drawRect(screenRect(r, scale, offX, offY), selectedLine)
    }

    /**
     * Walls as solid bars in their type's colour, drawn over the rooms. Lower
     * priority types go first, so External ends up on top where walls meet.
     * [highlight] is the key of a wall to outline (the one being edited).
     */
    fun drawWalls(c: Canvas, layout: WallLayout, scale: Float, offX: Float, offY: Float, highlight: String? = null) {
        fun screen(m: DoubleArray): RectF {
            val rf = RectF(
                offX + (m[0] * scale).toFloat(), offY + (m[1] * scale).toFloat(),
                offX + (m[2] * scale).toFloat(), offY + (m[3] * scale).toFloat(),
            )
            // Keep thin walls visible when zoomed far out.
            val minPx = 1.5f
            if (rf.width() < minPx) rf.inset((rf.width() - minPx) / 2, 0f)
            if (rf.height() < minPx) rf.inset(0f, (rf.height() - minPx) / 2)
            return rf
        }
        for (w in layout.walls.sortedBy { it.type.ordinal }) {
            wallFill.color = WallColors[w.type]
            c.drawRect(screen(w.rect), wallFill)
        }
        for (k in layout.corners.sortedBy { it.type.ordinal }) {
            wallFill.color = WallColors[k.type]
            c.drawRect(screen(k.rect), wallFill)
        }
        if (highlight != null) {
            for (w in layout.walls) if (w.key == highlight) c.drawRect(screen(w.rect).apply { inset(-3f * d, -3f * d) }, wallHighlight)
        }
    }

    /**
     * Width inside the top wall (or the bottom one if the top is covered),
     * depth rotated inside the left wall (or the right). Returns the walls used.
     */
    private fun drawEdgeDimensions(c: Canvas, r: Room, rect: RectF, covers: List<RectF>): Set<Side> {
        val used = mutableSetOf<Side>()
        val pad = 4f * d
        val size = dimText.textSize
        if (rect.height() < size * 5) return used
        fun clear(box: RectF) = covers.none { RectF.intersects(it, box) }

        val wLabel = "${fmtM(r.width)} m"
        val tw = dimText.measureText(wLabel)
        if (tw + 2 * pad < rect.width()) {
            val top = RectF(rect.centerX() - tw / 2, rect.top + pad, rect.centerX() + tw / 2, rect.top + pad + size * 1.2f)
            val bottom = RectF(top.left, rect.bottom - pad - size * 1.2f, top.right, rect.bottom - pad)
            when {
                clear(top) -> { c.drawText(wLabel, rect.centerX(), rect.top + pad + size, dimText); used += Side.TOP }
                clear(bottom) -> { c.drawText(wLabel, rect.centerX(), rect.bottom - pad - size * 0.25f, dimText); used += Side.BOTTOM }
            }
        }

        val dLabel = "${fmtM(r.depth)} m"
        val td = dimText.measureText(dLabel)
        if (td + 2 * pad < rect.height() && rect.width() > size * 5) {
            val cy = rect.centerY()
            // Rotated -90°, the glyphs sit to the left of the anchor x.
            fun drawAt(ax: Float) {
                c.save()
                c.rotate(-90f, ax, cy)
                c.drawText(dLabel, ax, cy, dimText)
                c.restore()
            }
            val leftX = rect.left + pad + size
            val rightX = rect.right - pad
            when {
                clear(RectF(leftX - size, cy - td / 2, leftX, cy + td / 2)) -> { drawAt(leftX); used += Side.LEFT }
                clear(RectF(rightX - size, cy - td / 2, rightX, cy + td / 2)) -> { drawAt(rightX); used += Side.RIGHT }
            }
        }
        return used
    }

    /**
     * Name, net area and height, shrunk or trimmed to fit. Tried in every
     * uncovered block of the room; the block that shows the most lines at the
     * largest size wins.
     */
    private fun drawCentreLabel(c: Canvas, plan: Plan, r: Room, rect: RectF, visible: List<RectF>, sides: Set<Side>) {
        val all = listOf(
            r.name to nameText,
            "${fmtArea(plan.netArea(r))} m²" to infoText,
            "h ${fmtM(r.height)} m" to infoText,
        )
        var best: Triple<RectF, Int, Float>? = null
        for (block in visible.take(20)) {
            val area = labelArea(rect, block, sides) ?: continue
            for (n in all.size downTo 1) {
                val lines = all.take(n)
                val needW = lines.maxOf { (t, p) -> p.measureText(t) }
                val needH = lines.sumOf { (_, p) -> (p.textSize * 1.2f).toDouble() }.toFloat()
                val s = min(1f, min(area.width() / needW, area.height() / needH))
                if (s < 0.6f) continue
                val b = best
                if (b == null || n > b.second || (n == b.second && s > b.third)) best = Triple(area, n, s)
                break
            }
        }
        val (area, n, s) = best ?: return
        val lines = all.take(n)
        val needH = lines.sumOf { (_, p) -> (p.textSize * 1.2f).toDouble() }.toFloat()
        var y = area.centerY() - needH * s / 2
        for ((text, paint) in lines) {
            val p = TextPaint(paint).apply { textSize = paint.textSize * s }
            y += p.textSize * 1.2f
            c.drawText(text, area.centerX(), y - p.textSize * 0.25f, p)
        }
    }

    /** [block] shrunk by padding, and kept clear of any edge dimension running along its side. */
    private fun labelArea(rect: RectF, block: RectF, sides: Set<Side>): RectF? {
        val pad = 4f * d
        val reserve = dimText.textSize * 1.4f
        val eps = 1f
        val area = RectF(
            block.left + pad + if (Side.LEFT in sides && block.left <= rect.left + eps) reserve else 0f,
            block.top + pad + if (Side.TOP in sides && block.top <= rect.top + eps) reserve else 0f,
            block.right - pad - if (Side.RIGHT in sides && block.right >= rect.right - eps) reserve else 0f,
            block.bottom - pad - if (Side.BOTTOM in sides && block.bottom >= rect.bottom - eps) reserve else 0f,
        )
        return area.takeIf { it.width() > 0 && it.height() > 0 }
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
            val layout = plan.wallLayout()
            val totals = layout.totals()
            val bounds = plan.bounds(layout) ?: doubleArrayOf(0.0, 0.0, 4.0, 3.0)
            val minX = bounds[0]
            val minY = bounds[1]
            val bw = max(bounds[2] - minX, 1.0)
            val bh = max(bounds[3] - minY, 1.0)
            val scale = (2000.0 / max(bw, bh)).toFloat()
            val planW = (bw * scale).toFloat()
            val planH = (bh * scale).toFloat()

            val tableTop = headerH + planH + 2 * margin
            val hasOverlap = rooms.any { plan.netArea(it) < round3(it.area) }
            val wallTableH = if (totals.isEmpty()) 0f else rowH * (totals.size + 2) + 12f * d
            val tableH = rowH * (rooms.size + if (hasOverlap) 3 else 2) + 12f * d + wallTableH
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
            for (room in rooms) r.drawRoom(c, plan, layout, room, scale, offX, offY, false)
            r.drawWalls(c, layout, scale, offX, offY)

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
            if (totals.isNotEmpty()) {
                drawWallSchedule(c, totals, margin, tableTop + tableH - wallTableH + 12f * d, width - 2 * margin, rowH, d)
            }
            return bmp
        }

        /** Total wall length by type and thickness, with each type's colour. */
        private fun drawWallSchedule(
            c: Canvas, totals: List<Triple<WallType, Double, Double>>,
            left: Float, top: Float, w: Float, rowH: Float, d: Float,
        ) {
            val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A1A.toInt(); textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
            }
            val body = TextPaint(head).apply { color = 0xFF263238.toInt(); typeface = Typeface.DEFAULT }
            val line = Paint().apply { color = 0xFF90A4AE.toInt(); strokeWidth = d }
            val swatch = Paint()
            val sw = 22f * d
            fun row(y: Float, cells: List<String>, p: TextPaint) {
                p.textAlign = Paint.Align.LEFT
                c.drawText(cells[0], left + sw + 8f * d, y, p)
                p.textAlign = Paint.Align.RIGHT
                c.drawText(cells[1], left + w * 0.70f, y, p)
                c.drawText(cells[2], left + w, y, p)
            }
            var y = top + rowH * 0.7f
            row(y, listOf("Wall", "Thickness mm", "Length m"), head)
            c.drawLine(left, y + rowH * 0.3f, left + w, y + rowH * 0.3f, line)
            for ((type, thickness, length) in totals) {
                y += rowH
                swatch.color = WallColors[type]
                c.drawRect(left, y - rowH * 0.55f, left + sw, y + rowH * 0.05f, swatch)
                row(y, listOf(type.label, mm(thickness), fmtM(length)), body)
            }
            c.drawLine(left, y + rowH * 0.3f, left + w, y + rowH * 0.3f, line)
            y += rowH
            body.textAlign = Paint.Align.LEFT
            c.drawText("Lengths are measured along the inside face of each room.", left, y, body)
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
                val net = plan.netArea(r)
                val mark = if (net < round3(r.area)) " *" else ""
                row(y, listOf(r.name + mark, fmtM(r.width), fmtM(r.depth), fmtM(r.height), fmtM(net), fmtM(plan.netVolume(r))), body)
            }
            c.drawLine(left, y + rowH * 0.3f, left + w, y + rowH * 0.3f, line)
            y += rowH
            row(y, listOf("Total", "", "", "", fmtM(plan.totalArea), fmtM(plan.totalVolume)), head)
            if (plan.rooms.any { plan.netArea(it) < round3(it.area) }) {
                y += rowH
                body.textAlign = Paint.Align.LEFT
                c.drawText("* Area and volume exclude the part covered by a room on top.", left, y, body)
            }
        }
    }
}
