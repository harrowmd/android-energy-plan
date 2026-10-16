package com.manytwo.energyplan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * The drawing surface.
 *  - One finger on empty space (or on a room that isn't selected) pans the
 *    plan - or, with [oneFingerDraw] on, drags out a new room.
 *  - Long-press a room to select it (the top one, where rooms overlap) - a
 *    short tap doesn't, so rooms aren't selected by accident. Keep holding
 *    and drag, or drag the selected room later, to move it. A tap on empty
 *    space clears the selection.
 *  - Double-tap a room to edit its sizes.
 *  - Long-press a wall to change its type or thickness.
 *  - Two fingers pan and pinch-zoom.
 * Only the rooms on [floor] are shown and can be touched; the nearest other
 * floor with rooms is drawn faintly underneath, to line rooms up against.
 * Corners and edges snap to other rooms' edges when close, otherwise to a
 * 10 cm grid. Rooms that share a wall touch: the wall is drawn over the join.
 */
class PlanView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onRoomDrawn(x: Double, y: Double, width: Double, depth: Double)
        fun onSelectionChanged(room: Room?)
        fun onEditRequested(room: Room)
        fun onBeforeMove()
        fun onRoomMoved()
        fun onWallLongPress(wall: Wall)
    }

    var listener: Listener? = null

    /** Off by default: one-finger drawing made it too easy to create rooms by accident. */
    var oneFingerDraw = false

    var plan: Plan = Plan()
        set(value) {
            field = value
            select(null)
        }

    var selectedId: Long? = null
        private set

    /** The floor being shown. */
    var floor: Floor = Floor.GROUND
        set(value) {
            field = value
            if (plan.room(selectedId)?.floor != value) select(null)
            invalidate()
        }

    /** Key of the wall to outline, while its dialog is open. */
    var highlightWall: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val renderer = PlanRenderer(d)
    private val gridLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF78909C.toInt(); textSize = 11f * d }

    // View transform: screen = world(m) * scale + offset
    private var scale = 40f * d
    private var offX = 24f * d
    private var offY = 24f * d
    private var fitted = false

    private enum class Mode { IDLE, DRAW, MOVE, PAN, PINCH, LONG_PRESS }

    private var mode = Mode.IDLE
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private var draftVisible = false
    private var draftX0 = 0.0
    private var draftY0 = 0.0
    private var draftX1 = 0.0
    private var draftY1 = 0.0

    private var moveRoom: Room? = null
    private var moveOrigX = 0.0
    private var moveOrigY = 0.0

    /** Snap targets, worked out once when a drag starts. */
    private var xSnap = emptyList<Double>()
    private var ySnap = emptyList<Double>()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val newScale = (scale * det.scaleFactor).coerceIn(MIN_SCALE * d, MAX_SCALE * d)
            val f = newScale / scale
            offX = det.focusX - (det.focusX - offX) * f
            offY = det.focusY - (det.focusY - offY) * f
            scale = newScale
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (roomAt(e.x, e.y) == null) select(null)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            roomAt(e.x, e.y)?.let {
                select(it.id)
                listener?.onEditRequested(it)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.IDLE) return
            // On a wall itself it's the wall; inside a room the room; just outside a wall, the wall again.
            val wall = wallAt(e.x, e.y, WALL_ON_DP)
            val room = if (wall == null) roomAt(e.x, e.y) else null
            val nearWall = if (wall == null && room == null) wallAt(e.x, e.y, WALL_REACH_DP) else wall
            when {
                room != null -> {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    select(room.id)
                    // Dragging on from here moves the room, measured from where the finger is now.
                    downX = e.x
                    downY = e.y
                }
                nearWall != null -> {
                    // The rest of this touch is ignored, so lifting the finger doesn't pan or move anything.
                    mode = Mode.LONG_PRESS
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener?.onWallLongPress(nearWall)
                }
            }
        }
    })

    fun select(id: Long?) {
        selectedId = plan.room(id)?.id
        listener?.onSelectionChanged(plan.room(selectedId))
        invalidate()
    }

    /** Hides the dashed outline left on screen while the new-room dialog is open. */
    fun clearDraft() {
        draftVisible = false
        invalidate()
    }

    fun fitToPlan() {
        if (width == 0 || height == 0) {
            fitted = false
            return
        }
        val bounds = plan.bounds(floor = floor) ?: plan.bounds()
        if (bounds == null) {
            scale = 40f * d
            offX = 24f * d
            offY = 24f * d
        } else {
            val minX = bounds[0]
            val minY = bounds[1]
            val bw = max(bounds[2] - minX, 1.0)
            val bh = max(bounds[3] - minY, 1.0)
            val m = 32f * d
            scale = min((width - 2 * m) / bw, (height - 2 * m) / bh).toFloat().coerceIn(MIN_SCALE * d, MAX_SCALE * d)
            offX = ((width - bw * scale) / 2 - minX * scale).toFloat()
            offY = ((height - bh * scale) / 2 - minY * scale).toFloat()
        }
        fitted = true
        invalidate()
    }

    /**
     * A sensible spot for a room added from the keyboard: touching the right
     * of everything else on this floor, or at the top-left of the building
     * when the floor is still empty.
     */
    fun freeSpot(): Pair<Double, Double> {
        val rooms = plan.rooms(floor)
        if (rooms.isEmpty()) {
            if (plan.rooms.isEmpty()) return 0.0 to 0.0
            return plan.rooms.minOf { it.x } to plan.rooms.minOf { it.y }
        }
        return rooms.maxOf { it.x + it.width } to rooms.minOf { it.y }
    }

    /** The nearest other floor with rooms (below first), drawn faintly as a guide. */
    private fun guideFloor(): Floor? =
        plan.floors().filter { it != floor }.minWithOrNull(compareBy({ abs(it.ordinal - floor.ordinal) }, { it.ordinal > floor.ordinal }))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted) fitToPlan()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val minor = renderer.drawGrid(canvas, scale, offX, offY, width, height)

        val guide = guideFloor()
        guide?.let { g -> for (r in plan.rooms(g)) renderer.drawGuide(canvas, r, scale, offX, offY) }
        val layout = plan.wallLayout().on(floor)
        for (r in plan.rooms(floor)) renderer.drawRoom(canvas, plan, layout, r, scale, offX, offY, r.id == selectedId)
        if (guide != null) {
            // Unallocated space is open to view: the guide floor's rooms show through its hatching.
            val open = Path()
            for (r in plan.rooms(floor)) if (r.unallocated) for (v in plan.visibleRects(r)) {
                open.addRect(sx(v[0]), sy(v[1]), sx(v[2]), sy(v[3]), Path.Direction.CW)
            }
            if (!open.isEmpty) {
                canvas.save()
                canvas.clipPath(open)
                for (r in plan.rooms(guide)) renderer.drawGuide(canvas, r, scale, offX, offY)
                canvas.restore()
            }
        }
        renderer.drawWalls(canvas, layout, scale, offX, offY, highlightWall)
        plan.room(selectedId)?.let { renderer.drawSelection(canvas, it, scale, offX, offY) }

        if (draftVisible) {
            val rect = RectF(sx(min(draftX0, draftX1)), sy(min(draftY0, draftY1)), sx(max(draftX0, draftX1)), sy(max(draftY0, draftY1)))
            renderer.drawDraft(canvas, rect, abs(draftX1 - draftX0), abs(draftY1 - draftY0))
        }

        val gridText = (if (minor < 1) "Grid ${(minor * 100).toInt()} cm" else "Grid ${minor.toInt()} m") + " · snap 10 cm"
        canvas.drawText(gridText, 8f * d, height - 8f * d, gridLabel)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) draftVisible = false
                if (mode == Mode.MOVE) finishMove()
                mode = Mode.PINCH
                setFocus(e, -1)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.PINCH) setFocus(e, e.actionIndex)

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PINCH -> {
                    val (fx, fy) = focus(e, -1)
                    offX += fx - lastFocusX
                    offY += fy - lastFocusY
                    lastFocusX = fx
                    lastFocusY = fy
                    invalidate()
                }
                Mode.IDLE -> if (hypot(e.x - downX, e.y - downY) > touchSlop) startDrag(e)
                Mode.DRAW -> updateDraft(e.x, e.y)
                Mode.MOVE -> updateMove(e.x, e.y)
                Mode.LONG_PRESS -> {}
                Mode.PAN -> {
                    offX += e.x - lastFocusX
                    offY += e.y - lastFocusY
                    lastFocusX = e.x
                    lastFocusY = e.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.DRAW -> finishDraw()
                    Mode.MOVE -> finishMove()
                    else -> {}
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.MOVE) finishMove()
                draftVisible = false
                mode = Mode.IDLE
                invalidate()
            }
        }
        return true
    }

    private fun startDrag(e: MotionEvent) {
        val r = roomAt(downX, downY)
        val moving = if (r != null && r.id == selectedId) r.id else null
        xSnap = snapLines(moving, horizontal = true)
        ySnap = snapLines(moving, horizontal = false)
        if (r != null && r.id == selectedId) {
            mode = Mode.MOVE
            moveRoom = r
            moveOrigX = r.x
            moveOrigY = r.y
            listener?.onBeforeMove()
            updateMove(e.x, e.y)
        } else if (!oneFingerDraw) {
            mode = Mode.PAN
            lastFocusX = e.x
            lastFocusY = e.y
        } else {
            mode = Mode.DRAW
            draftX0 = snapCoord(wx(downX), xSnap)
            draftY0 = snapCoord(wy(downY), ySnap)
            draftVisible = true
            updateDraft(e.x, e.y)
        }
    }

    private fun updateDraft(x: Float, y: Float) {
        draftX1 = snapCoord(wx(x), xSnap)
        draftY1 = snapCoord(wy(y), ySnap)
        invalidate()
    }

    private fun finishDraw() {
        val w = round3(abs(draftX1 - draftX0))
        val dd = round3(abs(draftY1 - draftY0))
        if (w >= MIN_ROOM && dd >= MIN_ROOM) {
            // The draft stays visible until the activity's dialog calls clearDraft().
            listener?.onRoomDrawn(round3(min(draftX0, draftX1)), round3(min(draftY0, draftY1)), w, dd)
        } else {
            clearDraft()
        }
    }

    private fun updateMove(x: Float, y: Float) {
        val r = moveRoom ?: return
        r.x = round3(snapEdge(moveOrigX + (x - downX) / scale, r.width, xSnap))
        r.y = round3(snapEdge(moveOrigY + (y - downY) / scale, r.depth, ySnap))
        invalidate()
    }

    private fun finishMove() {
        val r = moveRoom ?: return
        moveRoom = null
        listener?.onRoomMoved()
        listener?.onSelectionChanged(r)
    }

    // --- snapping ---

    private fun snapThreshold() = SNAP_DP * d / scale

    private fun snapLines(exclude: Long?, horizontal: Boolean) =
        plan.rooms.filter { it.id != exclude }.flatMap {
            if (horizontal) listOf(it.x, it.x + it.width) else listOf(it.y, it.y + it.depth)
        }

    private fun snapCoord(v: Double, lines: List<Double>): Double {
        val best = lines.minByOrNull { abs(it - v) }
        if (best != null && abs(best - v) < snapThreshold()) return best
        return round(v / GRID) * GRID
    }

    /** Snaps a room's position so either of its edges (pos or pos+size) lines up with another room's edge. */
    private fun snapEdge(pos: Double, size: Double, lines: List<Double>): Double {
        var bestShift = Double.MAX_VALUE
        for (l in lines) {
            for (edge in doubleArrayOf(pos, pos + size)) {
                val shift = l - edge
                if (abs(shift) < abs(bestShift)) bestShift = shift
            }
        }
        if (abs(bestShift) < snapThreshold()) return pos + bestShift
        return round(pos / GRID) * GRID
    }

    // --- coordinates ---

    private fun wx(sx: Float) = ((sx - offX) / scale).toDouble()
    private fun wy(sy: Float) = ((sy - offY) / scale).toDouble()
    private fun sx(wx: Double) = (offX + wx * scale).toFloat()
    private fun sy(wy: Double) = (offY + wy * scale).toFloat()

    private fun roomAt(sx: Float, sy: Float): Room? {
        val x = wx(sx)
        val y = wy(sy)
        // Topmost room in the stacking order.
        return plan.rooms.lastOrNull { it.floor == floor && it.contains(x, y) }
    }

    /** The wall nearest the touch, if one is within reach of the finger. */
    private fun wallAt(sx: Float, sy: Float, reachDp: Float): Wall? {
        val x = wx(sx)
        val y = wy(sy)
        val reach = reachDp * d / scale
        return plan.wallLayout().on(floor).walls
            .map { w ->
                val rc = w.rect
                val dx = max(0.0, max(rc[0] - x, x - rc[2]))
                val dy = max(0.0, max(rc[1] - y, y - rc[3]))
                w to hypot(dx, dy)
            }
            .filter { it.second <= reach }
            .minByOrNull { it.second }?.first
    }

    private fun focus(e: MotionEvent, skipIndex: Int): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        return if (n == 0) lastFocusX to lastFocusY else sx / n to sy / n
    }

    private fun setFocus(e: MotionEvent, skipIndex: Int) {
        val (fx, fy) = focus(e, skipIndex)
        lastFocusX = fx
        lastFocusY = fy
    }

    companion object {
        private const val MIN_SCALE = 2f      // dp per metre
        private const val MAX_SCALE = 1000f
        private const val SNAP_DP = 12f
        private const val GRID = 0.1          // metres
        private const val WALL_REACH_DP = 14f
        /** A long-press this close to a wall picks the wall even inside a room. */
        private const val WALL_ON_DP = 6f
        private const val MIN_ROOM = 0.1      // metres
    }
}
