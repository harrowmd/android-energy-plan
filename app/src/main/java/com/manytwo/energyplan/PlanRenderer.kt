package com.manytwo.energyplan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
    private val fillUnallocated = Paint().apply { color = 0xFFF1F3F4.toInt() }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFD8DC.toInt(); strokeWidth = 1f * d }
    /** The room's inner face; the walls themselves are drawn outside it by [drawWalls]. */
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263238.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val selectedLine = Paint(outline).apply { color = 0xFFE67E00.toInt(); strokeWidth = 3.5f * d }
    private val wallFill = Paint()
    private val bayWall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE
    }
    private val wallHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
    private val guideLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90A4AE.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
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
        if (r.unallocated) {
            c.drawRect(full, if (selected) fillSelected else fillUnallocated)
            // Diagonal hatching, so unallocated space can't be mistaken for a room.
            c.save()
            c.clipRect(full)
            // Lines of constant x + y, on a fixed spacing, so neighbouring spaces' hatching joins up.
            val step = 10f * d
            var k = ceil((full.left + full.top) / step) * step
            while (k < full.right + full.bottom) {
                c.drawLine(k - full.bottom, full.bottom, k - full.top, full.top, hatch)
                k += step
            }
            c.restore()
            // Just the hatching: no outline, sizes or label, as it isn't a room.
            return
        }
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

    /** A room on another floor: a faint dashed outline to line this floor's rooms up against. */
    fun drawGuide(c: Canvas, r: Room, scale: Float, offX: Float, offY: Float) {
        c.drawRect(screenRect(r, scale, offX, offY), guideLine)
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
    fun drawWalls(c: Canvas, layout: WallLayout, scale: Float, offX: Float, offY: Float, highlight: String? = null, selectedId: Long? = null) {
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
            val bays = w.bays
            if (bays.isEmpty()) {
                c.drawRect(screen(w.rect), wallFill)
                continue
            }
            // The straight wall, with a gap where each bay opens out of it.
            val rc = w.rect
            var parts = listOf(w.from to w.to)
            for (b in bays) {
                val (a, z) = b.span(w.room)
                parts = parts.flatMap { (p, q) -> listOf(p to min(q, a), max(p, z) to q).filter { it.second - it.first > 1e-6 } }
            }
            for ((p, q) in parts) {
                c.drawRect(screen(if (w.side.horizontal) doubleArrayOf(p, rc[1], q, rc[3]) else doubleArrayOf(rc[0], p, rc[2], q)), wallFill)
            }
        }
        for (w in layout.walls) for (b in w.bays) drawBay(c, w, b, scale, offX, offY, w.room.id == selectedId)
        for (k in layout.corners.sortedBy { it.type.ordinal }) {
            wallFill.color = WallColors[k.type]
            c.drawRect(screen(k.rect), wallFill)
        }
        if (highlight != null) {
            for (w in layout.walls) if (w.key == highlight) c.drawRect(screen(w.rect).apply { inset(-3f * d, -3f * d) }, wallHighlight)
        }
    }

    /**
     * A bay window: the wall follows its outline (its full thickness outside
     * the bay), then the bay's floor is filled like the room it belongs to.
     */
    private fun drawBay(c: Canvas, w: Wall, b: Bay, scale: Float, offX: Float, offY: Float, selected: Boolean) {
        val pts = b.outline(w.room).map { (offX + it[0] * scale).toFloat() to (offY + it[1] * scale).toFloat() }
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for ((x, y) in pts.drop(1)) lineTo(x, y)
        }
        // Stroked twice as thick, centred on the outline; the room and the bay's own floor cover the inner half.
        bayWall.color = WallColors[w.type]
        bayWall.strokeWidth = max(1.5f, (2 * w.thickness * scale).toFloat())
        c.save()
        c.clipOutRect(screenRect(w.room, scale, offX, offY))
        c.drawPath(path, bayWall)
        c.restore()
        val floor = when {
            selected -> fillSelected
            w.room.unallocated -> fillUnallocated
            else -> fill
        }
        c.drawPath(Path(path).apply { close() }, floor)
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
            "h ${fmtM(r.height)} m" + (if (r.part != BuildingPart.MAIN) "  ·  ${r.part.label}" else "") to infoText,
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

        /** A4 at about 215 dpi, in pixels; the PDF scales the same drawing to 595 × 842 pt. */
        const val A4_SHORT = 1780
        const val A4_LONG = 2517

        /**
         * One A4 page of the export: a floor, or the summary (which can run
         * over several pages). [name] goes in the file name, e.g. "Ground"
         * or "Summary-2". [draw] paints it at [width] × [height] pixels.
         */
        class Page(val name: String, val width: Int, val height: Int, val draw: (Canvas) -> Unit) {
            fun bitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(Color.WHITE)
                draw(c)
            }
        }

        /**
         * A line of a table or note on a summary page: its height and how to
         * draw it at a given top. [header] is its table's heading row, repeated
         * when the table carries on over a page break.
         */
        private class Line(val h: Float, val header: Line? = null, val draw: (Canvas, Float) -> Unit)

        private class Paints(d: Float) {
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A1A.toInt(); textSize = 22f * d; typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextPaint(title).apply { color = 0xFF546E7A.toInt(); textSize = 13f * d; typeface = Typeface.DEFAULT }
            val section = TextPaint(title).apply { textSize = 16f * d }
            val head = TextPaint(title).apply { textSize = 12f * d }
            val body = TextPaint(head).apply { color = 0xFF263238.toInt(); typeface = Typeface.DEFAULT }
            val muted = TextPaint(body).apply { color = 0xFF78909C.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
            val rule = Paint().apply { color = 0xFF90A4AE.toInt(); strokeWidth = d }
            val bar = Paint().apply { color = 0xFF263238.toInt(); strokeWidth = 2f * d }
        }

        /**
         * The export, page by page: one A4 page per floor (its drawing and its
         * dimensions table), then the summary - dimensions, wall schedule and
         * room schedule - over as many portrait pages as it needs. All floors
         * share one scale and position, so they line up page to page; floor
         * pages are landscape when the building is wider than it is deep.
         */
        fun pages(plan: Plan): List<Page> {
            val d = EXPORT_D
            val p = Paints(d)
            val margin = 32f * d
            val headerH = 64f * d
            val rowH = 18f * d
            val date = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date())

            val layout = plan.wallLayout()
            val dims = plan.dimensions(layout)
            val floors = plan.floors().ifEmpty { listOf(Floor.GROUND) }
            val boxes = floors.map { plan.bounds(layout, it) ?: doubleArrayOf(0.0, 0.0, 4.0, 3.0) }
            val minX = boxes.minOf { it[0] }
            val minY = boxes.minOf { it[1] }
            val bw = max(boxes.maxOf { it[2] } - minX, 1.0)
            val bh = max(boxes.maxOf { it[3] } - minY, 1.0)
            val landscape = bw > bh
            val fw = if (landscape) A4_LONG else A4_SHORT
            val fh = if (landscape) A4_SHORT else A4_LONG
            fun tableH(rows: Int) = if (rows == 0) 0f else rowH * (rows + 1) + 8f * d
            val maxTable = floors.maxOf { f -> tableH(dims.count { it.floor == f }) }
            val planTop = headerH + margin / 2
            val planBottom = fh - margin - maxTable - margin / 2
            val scale = min((fw - 2 * margin) / bw, (planBottom - planTop) / bh).toFloat()
            val offX = (fw - bw * scale).toFloat() / 2 - (minX * scale).toFloat()
            val offY = planTop - (minY * scale).toFloat()

            fun header(c: Canvas, w: Int, heading: String, line: String) {
                c.drawText(heading, margin, 30f * d, p.title)
                c.drawText(line, margin, 50f * d, p.sub)
            }

            val out = mutableListOf<Page>()
            for (floor in floors) {
                out += Page(floor.label, fw, fh) { c ->
                    val r = PlanRenderer(d)
                    val rooms = plan.rooms(floor).filter { !it.unallocated }
                    header(
                        c, fw, "${plan.name} – ${floor.title}",
                        "$date  ·  ${rooms.size} rooms  ·  ${fmtArea(rooms.sumOf { plan.netArea(it) })} m²  ·  ${fmtArea(rooms.sumOf { plan.netVolume(it) })} m³",
                    )
                    // 1 m scale bar, top right (the same scale on every floor page).
                    val barY = 46f * d
                    val barX = fw - margin - scale - p.sub.measureText("1 m") - 6 * d
                    c.drawLine(barX, barY, barX + scale, barY, p.bar)
                    c.drawLine(barX, barY - 4 * d, barX, barY + 4 * d, p.bar)
                    c.drawLine(barX + scale, barY - 4 * d, barX + scale, barY + 4 * d, p.bar)
                    c.drawText("1 m", barX + scale + 6 * d, barY + 4 * d, p.sub)

                    c.save()
                    c.clipRect(0f, planTop, fw.toFloat(), planBottom)
                    r.drawGrid(c, scale, offX, offY, fw, fh)
                    c.restore()
                    val floorLayout = layout.on(floor)
                    for (room in plan.rooms(floor)) r.drawRoom(c, plan, floorLayout, room, scale, offX, offY, false)
                    r.drawWalls(c, floorLayout, scale, offX, offY)

                    val lines = dimLines(p, dims.filter { it.floor == floor }.map { it to DimStyle.ROW }, margin, fw - 2 * margin, rowH)
                    var y = fh - margin - lines.sumOf { it.h.toDouble() }.toFloat()
                    for (l in lines) { l.draw(c, y); y += l.h }
                }
            }

            // The summary flows over portrait pages.
            val sw = A4_SHORT
            val sh = A4_LONG
            val w = sw - 2 * margin
            val lines = mutableListOf<Line>()
            fun section(text: String) {
                lines += Line(rowH * 1.6f) { c, top -> c.drawText(text, margin, top + rowH * 1.1f, p.section) }
            }
            fun gap() { lines += Line(rowH) { _, _ -> } }
            if (dims.isNotEmpty()) {
                section("Dimensions")
                lines += dimLines(p, summaryRows(dims), margin, w, rowH)
                lines += noteLines(p, "HLP: heat loss perimeter (External, Sheltered and Glazing walls). PWL: party wall length. Unallocated space adds no area, but its outside walls count.", margin, w, rowH)
                gap()
            }
            val totals = layout.totals()
            if (totals.isNotEmpty()) {
                section("Walls")
                lines += wallLines(p, totals, margin, w, rowH, d)
                gap()
            }
            section("Rooms")
            lines += roomLines(p, plan, margin, w, rowH)

            val top0 = headerH + margin / 2
            val pagesLines = mutableListOf(mutableListOf<Line>())
            var used = 0f
            for (l in lines) {
                if (used + l.h > sh - margin - top0 && pagesLines.last().isNotEmpty()) {
                    pagesLines += mutableListOf<Line>()
                    used = 0f
                    l.header?.let {
                        pagesLines.last() += it
                        used += it.h
                    }
                }
                pagesLines.last() += l
                used += l.h
            }
            pagesLines.forEachIndexed { i, pl ->
                val name = if (i == 0) "Summary" else "Summary-${i + 1}"
                out += Page(name, sw, sh) { c ->
                    header(
                        c, sw, "${plan.name} – Summary" + if (pagesLines.size > 1) " (${i + 1} of ${pagesLines.size})" else "",
                        "$date  ·  ${plan.allocated.size} rooms  ·  ${floors.size} floor${if (floors.size == 1) "" else "s"}  ·  " +
                            "${fmtArea(plan.totalArea)} m²  ·  ${fmtArea(plan.totalVolume)} m³",
                    )
                    var y = top0
                    for (l in pl) { l.draw(c, y); y += l.h }
                }
            }
            return out
        }

        /** Every part and floor, then a subtotal for each part on more than one floor, then the grand total. */
        fun summaryRows(dims: List<DimRow>): List<Pair<DimRow, DimStyle>> {
            if (dims.isEmpty()) return emptyList()
            val out = mutableListOf<Pair<DimRow, DimStyle>>()
            for ((part, rows) in dims.groupBy { it.part }) {
                rows.forEach { out += it to DimStyle.ROW }
                if (rows.size > 1) out += rows.total(part = part) to DimStyle.SUBTOTAL
            }
            out += dims.total() to DimStyle.TOTAL
            return out
        }

        /** Draws [texts] in one table row: the first [leftCols] left-aligned at their column, the rest right-aligned. */
        private fun cells(c: Canvas, top: Float, rowH: Float, left: Float, w: Float, cols: List<Float>, leftCols: Int, texts: List<String>, paint: TextPaint) {
            texts.forEachIndexed { i, text ->
                paint.textAlign = if (i < leftCols) Paint.Align.LEFT else Paint.Align.RIGHT
                c.drawText(text, left + w * cols[i], top + rowH * 0.7f, paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }

        /** A note, word-wrapped to [w] so it never runs off the page. */
        private fun noteLines(p: Paints, text: String, left: Float, w: Float, rowH: Float): List<Line> {
            val wrapped = mutableListOf<String>()
            var line = ""
            for (word in text.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && p.body.measureText(tryLine) > w) {
                    wrapped += line
                    line = word
                } else {
                    line = tryLine
                }
            }
            if (line.isNotEmpty()) wrapped += line
            return wrapped.map { t -> Line(rowH) { c, top -> c.drawText(t, left, top + rowH * 0.7f, p.body) } }
        }

        /**
         * Building Part, Floor, Area, Height, HLP (heat loss perimeter: External,
         * Sheltered and Glazing walls) and PWL (party wall length).
         */
        private fun dimLines(p: Paints, rows: List<Pair<DimRow, DimStyle>>, left: Float, w: Float, rowH: Float): List<Line> {
            if (rows.isEmpty()) return emptyList()
            val cols = listOf(0.0f, 0.22f, 0.52f, 0.66f, 0.83f, 1.0f)
            val head = Line(rowH) { c, top ->
                cells(c, top, rowH, left, w, cols, 2, listOf("Building part", "Floor", "Area m²", "Height m", "HLP m", "PWL m"), p.head)
                c.drawLine(left, top + rowH, left + w, top + rowH, p.rule)
            }
            val out = mutableListOf(head)
            for ((r, style) in rows) out += Line(rowH, head) { c, top ->
                if (style == DimStyle.TOTAL) c.drawLine(left, top, left + w, top, p.rule)
                val name = when (style) {
                    DimStyle.ROW -> r.part?.label ?: ""
                    DimStyle.SUBTOTAL -> "  ${r.part?.label} total"
                    DimStyle.TOTAL -> "Total"
                }
                val floor = if (style == DimStyle.ROW) r.floor?.title ?: "" else ""
                cells(c, top, rowH, left, w, cols, 2, listOf(name, floor, fmtM(r.area), fmtM(r.height), fmtM(r.hlp), fmtM(r.pwl)), if (style == DimStyle.ROW) p.body else p.head)
            }
            return out
        }

        /** Total wall length by type and thickness, with each type's colour. */
        private fun wallLines(p: Paints, totals: List<Triple<WallType, Double, Double>>, left: Float, w: Float, rowH: Float, d: Float): List<Line> {
            val sw = 22f * d
            val swatch = Paint()
            fun row(c: Canvas, top: Float, a: String, b: String, cc: String, paint: TextPaint) {
                cells(c, top, rowH, left + sw + 8f * d, w - sw - 8f * d, listOf(0f, 0.70f, 1f), 1, listOf(a, b, cc), paint)
            }
            val head = Line(rowH) { c, top ->
                row(c, top, "Wall", "Thickness mm", "Length m", p.head)
                c.drawLine(left, top + rowH, left + w, top + rowH, p.rule)
            }
            val out = mutableListOf(head)
            for ((type, thickness, length) in totals) out += Line(rowH, head) { c, top ->
                swatch.color = WallColors[type]
                c.drawRect(left, top + rowH * 0.15f, left + sw, top + rowH * 0.75f, swatch)
                row(c, top, type.label, mm(thickness), fmtM(length), p.body)
            }
            out += noteLines(p, "Lengths are measured along the inside face of each room.", left, w, rowH)
            return out
        }

        /** Every room (unallocated space greyed, in brackets) with totals per floor and per part. */
        private fun roomLines(p: Paints, plan: Plan, left: Float, w: Float, rowH: Float): List<Line> {
            // Room, Floor and Part are left-aligned from their x; the numbers are right-aligned to theirs.
            val cols = listOf(0.0f, 0.27f, 0.39f, 0.59f, 0.68f, 0.77f, 0.875f, 1.0f)
            val head = Line(rowH) { c, top ->
                cells(c, top, rowH, left, w, cols, 3, listOf("Room", "Floor", "Part", "Width x", "Depth y", "Height z", "Area m²", "Volume m³"), p.head)
                c.drawLine(left, top + rowH, left + w, top + rowH, p.rule)
            }
            val out = mutableListOf(head)
            for (r in plan.rooms.sortedBy { it.floor }) out += Line(rowH, head) { c, top ->
                val net = plan.netArea(r)
                val mark = (if (net < round3(r.area)) " *" else "") + if (r.bays.isNotEmpty()) " †" else ""
                val (a, v) = if (r.unallocated) "(${fmtM(net)})" to "(${fmtM(plan.netVolume(r))})" else fmtM(net) to fmtM(plan.netVolume(r))
                cells(c, top, rowH, left, w, cols, 3, listOf(r.name + mark, r.floor.label, r.part.label, fmtM(r.width), fmtM(r.depth), fmtM(r.height), a, v), if (r.unallocated) p.muted else p.body)
            }
            out += Line(rowH) { c, top ->
                c.drawLine(left, top, left + w, top, p.rule)
                cells(c, top, rowH, left, w, cols, 3, listOf("Total", "", "", "", "", "", fmtM(plan.totalArea), fmtM(plan.totalVolume)), p.head)
            }
            fun rooms(n: Int) = "$n room" + if (n == 1) "" else "s"
            val floors = plan.floorTotals()
            if (floors.size > 1) for (t in floors) out += Line(rowH) { c, top ->
                cells(c, top, rowH, left, w, cols, 3, listOf("  ${t.floor.title} total", rooms(t.rooms), "", "", "", "", fmtM(t.area), fmtM(t.volume)), p.body)
            }
            val parts = plan.partTotals()
            if (parts.size > 1) for (t in parts) out += Line(rowH) { c, top ->
                cells(c, top, rowH, left, w, cols, 3, listOf("  ${t.part.label} total", rooms(t.rooms), "", "", "", "", fmtM(t.area), fmtM(t.volume)), p.body)
            }
            if (plan.rooms.any { plan.netArea(it) < round3(it.area) }) out += noteLines(p, "* Area and volume exclude the part covered by a room on top.", left, w, rowH)
            if (plan.rooms.any { it.bays.isNotEmpty() }) {
                out += noteLines(p, "† Area and volume include bay windows (width × depth is the main rectangle only).", left, w, rowH)
            }
            if (plan.rooms.any { it.unallocated }) {
                out += noteLines(p, "Unallocated space (grey, in brackets, ${fmtM(plan.unallocatedArea)} m² in all) is not included in the totals.", left, w, rowH)
            }
            return out
        }
    }
}
