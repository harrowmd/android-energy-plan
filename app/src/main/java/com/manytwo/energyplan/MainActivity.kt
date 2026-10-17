package com.manytwo.energyplan

import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.view.Gravity
import android.graphics.Typeface
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.manytwo.energyplan.databinding.ActivityMainBinding
import com.manytwo.energyplan.databinding.DialogRoomBinding
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), PlanView.Listener {

    private lateinit var b: ActivityMainBinding
    private var plan = Plan()
    private val undoStack = ArrayDeque<Plan>()
    private val prefs by lazy { getSharedPreferences("energyplan", MODE_PRIVATE) }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { PlanStorage.setCustomFolder(this, uri) }
            .onSuccess {
                AppLog.i("Save location: $uri")
                Toast.makeText(this, PlanStorage.locationLabel(this), Toast.LENGTH_LONG).show()
            }
            .onFailure {
                AppLog.e("Could not use folder $uri", it)
                Toast.makeText(this, "Can't save to that folder: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openPlan(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        // targetSdk 35+ is always edge-to-edge: keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        WallColors.load(prefs)
        plan = PlanStorage.loadAutosave(this) ?: Plan()
        AppLog.i("Opened plan '${plan.name}' with ${plan.rooms.size} rooms, ${fmtM(plan.totalArea)} m²")
        b.planView.listener = this
        b.planView.oneFingerDraw = prefs.getBoolean(PREF_ONE_FINGER_DRAW, false)
        b.planView.plan = plan
        b.planView.floor = startFloor(plan, Floor.parse(prefs.getString(PREF_VIEW_FLOOR, null)))

        b.addButton.setOnClickListener { addRoomFromKeyboard() }
        b.editButton.setOnClickListener { plan.room(b.planView.selectedId)?.let { editRoom(it) } }
        b.deleteButton.setOnClickListener { deleteSelected() }
        updateUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // In the toolbar the short name shows; when it overflows into ⋮ the full title makes clear what it does.
        menu.findItem(R.id.action_floor).apply {
            title = "Floor: ${b.planView.floor.title}…"
            titleCondensed = b.planView.floor.label
        }
        menu.findItem(R.id.action_undo).isEnabled = undoStack.isNotEmpty()
        // Each stacking action is only offered when it would change something.
        val room = plan.room(b.planView.selectedId)
        val up = room != null && plan.canBringForward(room)
        val down = room != null && plan.canSendBackward(room)
        menu.findItem(R.id.action_add_floor).apply {
            val above = room?.floor?.above
            isEnabled = above != null
            title = if (room != null && above != null) "Add floor: copy ${room.part.label} to ${above.title}" else "Add floor… (select a room)"
        }
        // Only offered from the ground floor, where the basement is the floor below.
        menu.findItem(R.id.action_add_basement).apply {
            isVisible = room?.floor == Floor.GROUND
            title = if (room != null) "Add basement: copy ${room.part.label} to Basement" else "Add basement"
        }
        menu.findItem(R.id.action_bring_forward).isEnabled = up
        menu.findItem(R.id.action_bring_front).isEnabled = up
        menu.findItem(R.id.action_send_backward).isEnabled = down
        menu.findItem(R.id.action_send_back).isEnabled = down
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_floor -> chooseFloor()
            R.id.action_undo -> undo()
            R.id.action_fit -> b.planView.fitToPlan()
            R.id.action_summary -> showSummary()
            R.id.action_add_floor -> plan.room(b.planView.selectedId)?.floor?.above?.let { addFloor(it) }
            R.id.action_add_basement -> addFloor(Floor.BASEMENT)
            R.id.action_bring_forward -> restack { plan.bringForward(it) }
            R.id.action_bring_front -> restack { plan.bringToFront(it) }
            R.id.action_send_backward -> restack { plan.sendBackward(it) }
            R.id.action_send_back -> restack { plan.sendToBack(it) }
            R.id.action_save -> askName("Save plan", "Save") { save() }
            R.id.action_save_location -> chooseSaveLocation()
            R.id.action_share_log -> shareLog()
            R.id.action_open -> openDocument.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            R.id.action_rename -> askName("Rename plan", "Rename") {}
            R.id.action_new -> confirmNewPlan()
            R.id.action_settings -> showSettings()
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- PlanView.Listener ---

    override fun onRoomDrawn(x: Double, y: Double, width: Double, depth: Double) {
        val draft = Room(plan.nextId(), "Room ${plan.rooms.size + 1}", x, y, width, depth, lastHeight(), lastPart(), b.planView.floor)
        showRoomDialog("New room", draft, onDismiss = { b.planView.clearDraft() }) { room ->
            pushUndo()
            plan.rooms.add(room)
            AppLog.i("Drew room ${room.describe()}")
            showFloor(room.floor)
            changed()
            b.planView.select(room.id)
        }
    }

    override fun onSelectionChanged(room: Room?) {
        updateUi()
        invalidateOptionsMenu()
    }

    override fun onEditRequested(room: Room) = editRoom(room)

    private var moveStart: Pair<Double, Double>? = null

    override fun onBeforeMove() {
        pushUndo()
        moveStart = plan.room(b.planView.selectedId)?.let { it.x to it.y }
    }

    override fun onRoomMoved() {
        val r = plan.room(b.planView.selectedId) ?: return changed()
        val start = moveStart
        if (start != null && start == (r.x to r.y)) {
            // Snapped back to where it started: nothing to log or undo.
            undoStack.removeLastOrNull()
            invalidateOptionsMenu()
            return
        }
        val from = start?.let { "(${fmtM(it.first)}, ${fmtM(it.second)})" } ?: "?"
        AppLog.i("Moved '${r.name}' from $from to (${fmtM(r.x)}, ${fmtM(r.y)})")
        changed()
    }

    override fun onWallLongPress(wall: Wall) = showWallDialog(wall)

    // --- rooms ---

    private fun addRoomFromKeyboard() {
        val (x, y) = b.planView.freeSpot()
        val draft = Room(plan.nextId(), "Room ${plan.rooms.size + 1}", x, y, 3.0, 3.0, lastHeight(), lastPart(), b.planView.floor)
        showRoomDialog("Add room", draft) { room ->
            pushUndo()
            plan.rooms.add(room)
            AppLog.i("Added room from keyboard ${room.describe()}")
            showFloor(room.floor)
            changed()
            b.planView.select(room.id)
            b.planView.fitToPlan()
        }
    }

    private fun editRoom(room: Room) {
        showRoomDialog("Edit room", room) { edited ->
            if (edited == room) return@showRoomDialog
            pushUndo()
            AppLog.i("Edited room ${room.describe()} -> ${edited.describe()}")
            // Undo snapshot holds copies, so it's safe to change the live room in place.
            room.name = edited.name
            room.width = edited.width
            room.depth = edited.depth
            room.height = edited.height
            room.part = edited.part
            room.floor = edited.floor
            showFloor(room.floor)
            changed()
            b.planView.select(room.id)
        }
    }

    /** Changes where the selected room sits in the stack; overlaps go to whichever room is on top. */
    private fun restack(move: (Room) -> Unit) {
        val room = plan.room(b.planView.selectedId) ?: return
        pushUndo()
        move(room)
        AppLog.i("Restacked '${room.name}': stack is now ${plan.rooms.joinToString(" < ") { "'${it.name}'" }}; net area ${fmtM(plan.netArea(room))} m²")
        changed()
    }

    /**
     * A room can go completely, or be made unallocated: its space and walls
     * stay exactly as they are, but it no longer counts as a room. An
     * unallocated space can go completely, or be restored as a room.
     */
    private fun deleteSelected() {
        val room = plan.room(b.planView.selectedId) ?: return
        val options = if (room.unallocated) {
            arrayOf("Delete completely – remove the space and its walls", "Restore as room '${room.name}'")
        } else {
            arrayOf("Delete completely – remove the room and its walls", "Make unallocated – keep the space and its walls, but not as a room")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (room.unallocated) "Unallocated space" else "Delete ${room.name}")
            .setItems(options) { _, which ->
                if (which == 0) deleteCompletely(room) else setUnallocated(room, !room.unallocated)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setUnallocated(room: Room, on: Boolean) {
        pushUndo()
        room.unallocated = on
        AppLog.i(if (on) "Made room unallocated: ${room.describe()}" else "Restored room: ${room.describe()}")
        changed()
        b.planView.select(room.id)
    }

    private fun deleteCompletely(room: Room) {
        pushUndo()
        plan.rooms.remove(room)
        plan.pruneWallOverrides()
        AppLog.i("Deleted room ${room.describe()}")
        changed()
        b.planView.select(null)
        Toast.makeText(this, "Deleted ${room.name} (Undo to restore)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Name + x/y/z size dialog. The OK button only closes it once all three
     * sizes parse as positive numbers, so a typo doesn't lose the drawn room.
     */
    private fun showRoomDialog(title: String, initial: Room, onDismiss: () -> Unit = {}, onOk: (Room) -> Unit) {
        val v = DialogRoomBinding.inflate(layoutInflater)
        v.nameInput.setText(initial.name)
        v.widthInput.setText(fmtM(initial.width))
        v.depthInput.setText(fmtM(initial.depth))
        v.heightInput.setText(fmtM(initial.height))
        val partButtons = mapOf(
            BuildingPart.MAIN to v.partMain, BuildingPart.EXT1 to v.partExt1, BuildingPart.EXT2 to v.partExt2,
            BuildingPart.EXT3 to v.partExt3, BuildingPart.EXT4 to v.partExt4,
            BuildingPart.CONSERVATORY to v.partConservatory,
        )
        v.partGroup.check(partButtons.getValue(initial.part).id)
        val floorChips = mapOf(
            Floor.BASEMENT to v.floorBasement, Floor.GROUND to v.floorGround, Floor.FIRST to v.floor1,
            Floor.SECOND to v.floor2, Floor.THIRD to v.floor3, Floor.FOURTH to v.floor4,
            Floor.FIFTH to v.floor5, Floor.SIXTH to v.floor6, Floor.SEVENTH to v.floor7,
        )
        v.floorGroup.check(floorChips.getValue(initial.floor).id)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(v.root)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { onDismiss() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w = parseSize(v.widthInput)
                val dd = parseSize(v.depthInput)
                val h = parseSize(v.heightInput)
                if (w == null || dd == null || h == null) return@setOnClickListener
                val name = v.nameInput.text.toString().trim().ifBlank { initial.name }
                val part = partButtons.entries.first { it.value.id == v.partGroup.checkedChipId }.key
                prefs.edit().putFloat("lastHeight", h.toFloat()).putString("lastPart", part.name).apply()
                val floor = floorChips.entries.first { it.value.id == v.floorGroup.checkedChipId }.key
                onOk(initial.copy(name = name, width = w, depth = dd, height = h, part = part, floor = floor))
                dialog.dismiss()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        v.widthInput.requestFocus()
        v.widthInput.selectAll()
    }

    private fun parseSize(field: EditText): Double? {
        val v = field.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (v == null || v <= 0 || v > 1000) {
            AppLog.w("Rejected size '${field.text}' in ${resources.getResourceEntryName(field.id)}")
            field.error = "Enter a size in metres, e.g. 3.25"
            return null
        }
        return round3(v)
    }

    /** New rooms start in the building part used last, since extensions tend to be drawn room after room. */
    private fun lastPart() = BuildingPart.parse(prefs.getString("lastPart", null)) ?: BuildingPart.MAIN

    private fun lastHeight() = prefs.getFloat("lastHeight", DEFAULT_HEIGHT.toFloat()).toDouble().let(::round3)

    // --- undo / state ---

    private fun pushUndo() {
        undoStack.addLast(plan.copy())
        if (undoStack.size > 50) undoStack.removeFirst()
        invalidateOptionsMenu()
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        AppLog.i("Undo (${undoStack.size} steps left)")
        val keepSelection = b.planView.selectedId
        setPlan(previous, fit = false)
        b.planView.select(keepSelection)
        PlanStorage.autosave(this, plan)
        invalidateOptionsMenu()
    }

    private fun changed() {
        PlanStorage.autosave(this, plan)
        b.planView.invalidate()
        updateUi()
        invalidateOptionsMenu()
    }

    private fun setPlan(p: Plan, fit: Boolean = true) {
        plan = p
        b.planView.plan = p
        b.planView.floor = startFloor(p, b.planView.floor)
        if (fit) b.planView.fitToPlan()
        updateUi()
    }

    private fun updateUi() {
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = "${plan.name}  ·  ${b.planView.floor.title}"
        val room = plan.room(b.planView.selectedId)
        b.status.text = when {
            room != null && room.unallocated -> String.format(
                Locale.UK, "Unallocated space (was %s) – not counted\n%s × %s m  ·  %s m²  ·  Delete to remove or restore",
                room.name, fmtM(room.width), fmtM(room.depth), fmtM(plan.netArea(room)),
            )
            room != null -> {
                val net = plan.netArea(room)
                val covered = round3(room.area) - net
                val title = "${room.name} (${room.part.label})"
                val heading = if (covered > 0) String.format(Locale.UK, "%s  ·  %s m² covered by rooms on top", title, fmtM(covered)) else title
                String.format(
                    Locale.UK, "%s\n%s × %s × %s m  ·  %s m²  ·  %s m³",
                    heading, fmtM(room.width), fmtM(room.depth), fmtM(room.height), fmtM(net), fmtM(plan.netVolume(room)),
                )
            }
            plan.rooms.isEmpty() && b.planView.oneFingerDraw -> "Drag on the grid to draw a room.\nTwo fingers to pan and zoom."
            plan.rooms.isEmpty() -> "Tap + Room to add a room.\nDrag to pan · pinch to zoom."
            else -> String.format(
                Locale.UK, "%s%d rooms  ·  %s m²  ·  %s m³\n%s",
                if (plan.floors().size > 1) "${b.planView.floor.title}: " else "",
                plan.rooms(b.planView.floor).count { !it.unallocated },
                fmtArea(plan.rooms(b.planView.floor).filter { !it.unallocated }.sumOf { plan.netArea(it) }),
                fmtArea(plan.rooms(b.planView.floor).filter { !it.unallocated }.sumOf { plan.netVolume(it) }),
                if (b.planView.oneFingerDraw) "Drag: draw · hold: select room / wall · drag selected: move" else "Drag: pan · hold: select room / wall · drag selected: move",
            )
        }
        b.editButton.isEnabled = room != null
        b.deleteButton.isEnabled = room != null
    }

    // --- files ---

    private fun askName(title: String, button: String, then: () -> Unit) {
        val input = EditText(this).apply {
            setText(plan.name)
            setSelection(text.length)
            setSingleLine()
        }
        val frame = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(button) { _, _ ->
                val old = plan.name
                plan.name = input.text.toString().trim().ifBlank { plan.name }
                if (plan.name != old) AppLog.i("Renamed plan '$old' -> '${plan.name}'")
                PlanStorage.autosave(this, plan)
                updateUi()
                then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val snapshot = plan.copy()
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        AppLog.i("Saving '${snapshot.name}': ${snapshot.rooms.size} rooms, ${fmtM(snapshot.totalArea)} m², ${fmtM(snapshot.totalVolume)} m³ to ${PlanStorage.locationLabel(this)}")
        AppLog.i("Walls: " + snapshot.wallLayout().totals().joinToString { (t, th, len) -> "${t.label} ${mm(th)} mm ${fmtM(len)} m" })
        thread {
            val start = System.currentTimeMillis()
            val result = runCatching { PlanStorage.save(this, snapshot) }
            result.onSuccess { AppLog.i("Save finished in ${System.currentTimeMillis() - start} ms") }
                .onFailure { AppLog.e("Save failed", it) }
            // Written last, so it records how the save went - even a failed one.
            val log = runCatching { PlanStorage.saveLog(this, snapshot) }.onFailure { AppLog.e("Could not write log file", it) }
            runOnUiThread {
                result.onSuccess { names ->
                    val all = names + listOfNotNull(log.getOrNull()?.let { "${PlanStorage.baseName(snapshot)}.txt" })
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Saved ${all.size} files")
                        .setMessage(PlanStorage.locationLabel(this) + "\n\n" + all.joinToString("\n"))
                        .setPositiveButton("OK", null)
                        .show()
                }.onFailure {
                    Toast.makeText(this, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Lets the user send saves to any folder they can pick (including cloud folders), or back to the defaults. */
    private fun chooseSaveLocation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Save location")
            .setMessage(PlanStorage.locationLabel(this))
            .setPositiveButton("Choose folder…") { _, _ -> pickFolder.launch(null) }
            .setNeutralButton("Use default") { _, _ ->
                PlanStorage.setCustomFolder(this, null)
                AppLog.i("Save location: default")
                Toast.makeText(this, "Saving to the default folders", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareLog() {
        val snapshot = plan.copy()
        thread {
            val result = runCatching { PlanStorage.saveLog(this, snapshot) }
            runOnUiThread {
                result.onSuccess { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Energy Plan log - ${snapshot.name}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share log"))
                }.onFailure {
                    AppLog.e("Share log failed", it)
                    Toast.makeText(this, "Couldn't write log: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openPlan(uri: Uri) {
        AppLog.i("Opening $uri")
        try {
            val loaded = Plan.fromJson(PlanStorage.readText(this, uri))
            AppLog.i("Opened '${loaded.name}' with ${loaded.rooms.size} rooms")
            pushUndo()
            setPlan(loaded)
            PlanStorage.autosave(this, plan)
        } catch (e: Exception) {
            AppLog.e("Open failed for $uri", e)
            MaterialAlertDialogBuilder(this)
                .setTitle("Can't open that file")
                .setMessage("It doesn't look like an Energy Plan JSON file.\n\n${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmNewPlan() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Start a new plan?")
            .setMessage("The current plan stays on screen until you confirm. Unsaved changes can still be brought back with Undo.")
            .setPositiveButton("New plan") { _, _ ->
                AppLog.i("New plan (previous '${plan.name}' had ${plan.rooms.size} rooms)")
                pushUndo()
                setPlan(Plan())
                PlanStorage.autosave(this, plan)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- walls ---

    /** Type and thickness of one wall; picking a type fills in its default thickness. */
    private fun showWallDialog(wall: Wall) {
        b.planView.highlightWall = wall.key
        val pad = dp(24)
        val group = RadioGroup(this)
        val buttons = WallType.menuOrder.associateWith { t ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = swatch(WallColors[t]).append("  ${t.label}  (${mm(t.defaultThickness)} mm)")
                group.addView(this)
            }
        }
        val thickness = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(mm(wall.thickness))
        }
        val thicknessLayout = TextInputLayout(this).apply {
            hint = "Thickness (mm)"
            addView(thickness)
        }
        buttons.getValue(wall.type).isChecked = true
        group.setOnCheckedChangeListener { _, id ->
            val t = buttons.entries.first { it.value.id == id }.key
            thickness.setText(mm(t.defaultThickness))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(group)
            addView(thicknessLayout)
        }
        val auto = if (wall.neighbour == null) WallType.EXTERNAL else WallType.INTERNAL
        val where = wall.neighbour?.let { "Between ${wall.room.name} and ${it.name}" } ?: "${wall.room.name}, ${wall.side.label} side"

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Wall")
            .setMessage("$where · ${fmtM(wall.length)} m long" + if (wall.custom) "" else "\nAutomatic: ${auto.label}")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Automatic", null)
            .setOnDismissListener { b.planView.highlightWall = null }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = buttons.entries.first { it.value.isChecked }.key
                val mmValue = thickness.text.toString().trim().toIntOrNull()
                if (mmValue == null || mmValue < 1 || mmValue > 2000) {
                    thicknessLayout.error = "Enter a thickness in mm, e.g. 350"
                    return@setOnClickListener
                }
                val spec = WallSpec(type, mmValue / 1000.0)
                if (spec == WallSpec(wall.type, wall.thickness)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                pushUndo()
                // Back to automatic when it matches what the app would pick anyway.
                if (spec == WallSpec(auto, auto.defaultThickness)) plan.wallOverrides.remove(wall.key)
                else plan.wallOverrides[wall.key] = spec
                AppLog.i("Wall changed: ${wall.describe()} -> ${type.label} $mmValue mm")
                changed()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = wall.custom
                setOnClickListener {
                    pushUndo()
                    plan.wallOverrides.remove(wall.key)
                    AppLog.i("Wall reset to automatic: ${wall.describe()}")
                    changed()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun swatch(color: Int) = SpannableStringBuilder("■■").apply {
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- summary ---

    /**
     * Area, height, heat loss perimeter and party wall length for every
     * building part on every floor, with subtotals - the same table as the
     * JPG's Summary.
     */
    private fun showSummary() {
        val rows = PlanRenderer.summaryRows(plan.dimensions())
        if (rows.isEmpty()) {
            Toast.makeText(this, "No rooms yet", Toast.LENGTH_SHORT).show()
            return
        }
        val table = TableLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            isStretchAllColumns = true
        }
        fun addRow(cells: List<String>, bold: Boolean) {
            table.addView(TableRow(this).apply {
                cells.forEachIndexed { i, text ->
                    addView(TextView(this@MainActivity).apply {
                        this.text = text
                        textSize = 14f
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                        gravity = if (i <= 1) Gravity.START else Gravity.END
                        if (bold) setTypeface(typeface, Typeface.BOLD)
                    })
                }
            })
        }
        addRow(listOf("Part", "Floor", "Area m²", "Height m", "HLP m", "PWL m"), true)
        for ((r, style) in rows) {
            val name = when (style) {
                DimStyle.ROW -> r.part?.label ?: ""
                DimStyle.SUBTOTAL -> "${r.part?.label} total"
                DimStyle.TOTAL -> "Total"
            }
            val floor = if (style == DimStyle.ROW) r.floor?.label ?: "" else ""
            addRow(listOf(name, floor, fmtM(r.area), fmtM(r.height), fmtM(r.hlp), fmtM(r.pwl)), style != DimStyle.ROW)
        }
        val note = TextView(this).apply {
            text = "HLP: heat loss perimeter (External, Sheltered and Glazing walls). PWL: party wall length. Unallocated space isn't counted."
            textSize = 12f
            setPadding(dp(26), dp(4), dp(26), dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { addView(table) })
            addView(note)
        }
        AppLog.i("Summary shown: ${rows.size} rows")
        MaterialAlertDialogBuilder(this)
            .setTitle("Summary – ${plan.name}")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("OK", null)
            .show()
    }

    // --- floors ---

    /**
     * The floor to show for [p]: [preferred] if it has rooms (or the plan is
     * empty), otherwise the lowest floor that has some.
     */
    private fun startFloor(p: Plan, preferred: Floor?): Floor {
        val floors = p.floors()
        return when {
            preferred != null && (preferred in floors || floors.isEmpty()) -> preferred
            floors.isNotEmpty() -> if (Floor.GROUND in floors) Floor.GROUND else floors.first()
            else -> Floor.GROUND
        }
    }

    private fun showFloor(floor: Floor) {
        if (floor == b.planView.floor) return
        b.planView.floor = floor
        prefs.edit().putString(PREF_VIEW_FLOOR, floor.name).apply()
        AppLog.i("Showing ${floor.title}")
        updateUi()
        invalidateOptionsMenu()
    }

    /** Copies the selected room's building part, on its floor, to floor [to] (the one above, or the basement). */
    private fun addFloor(to: Floor) {
        val room = plan.room(b.planView.selectedId) ?: return
        val part = room.part
        val from = room.floor
        if (to == from) return
        val count = plan.rooms.count { it.part == part && it.floor == from }
        val already = plan.rooms.count { it.part == part && it.floor == to }
        fun go() {
            pushUndo()
            val copies = plan.copyPart(part, from, to)
            AppLog.i("Added ${if (to == Floor.BASEMENT) "basement" else "floor"}: copied ${copies.size} ${part.label} rooms from ${from.title} to ${to.title}")
            showFloor(to)
            changed()
            Toast.makeText(this, "Copied ${copies.size} ${part.label} room${if (copies.size == 1) "" else "s"} to ${to.title} (Undo to remove)", Toast.LENGTH_LONG).show()
        }
        val msg = "Copies all $count ${part.label} room${if (count == 1) "" else "s"} on the ${from.title} to the ${to.title}, " +
            "with the same sizes and positions. Rename and resize them there as needed." +
            if (already > 0) "\n\nThe ${to.title} already has $already ${part.label} room${if (already == 1) "" else "s"}; the copies will be added on top." else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Add ${to.title} to ${part.label}?")
            .setMessage(msg)
            .setPositiveButton("Add floor") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFloor() {
        val floors = Floor.entries
        val items = floors.map { f ->
            val n = plan.rooms(f).size
            f.title + if (n > 0) "  ($n room${if (n == 1) "" else "s"})" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Show floor")
            .setSingleChoiceItems(items, floors.indexOf(b.planView.floor)) { dialog, which ->
                // Keeps the current zoom and pan, so floors line up as you switch.
                showFloor(floors[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- settings ---

    private fun showSettings() {
        val items = mutableListOf<CharSequence>(
            "One finger create room: " + if (b.planView.oneFingerDraw) "Yes" else "No",
        )
        for (t in WallType.menuOrder) items += swatch(WallColors[t]).append("  ${t.label} wall colour")
        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) showOneFingerSetting() else pickWallColour(WallType.menuOrder[which - 1])
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showOneFingerSetting() {
        val options = arrayOf("No (default) – one finger pans the plan", "Yes – one-finger drag draws a room")
        var choice = if (b.planView.oneFingerDraw) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("One finger create room")
            .setSingleChoiceItems(options, choice) { _, which -> choice = which }
            .setPositiveButton("OK") { _, _ ->
                val on = choice == 1
                if (on != b.planView.oneFingerDraw) AppLog.i("Setting: one-finger create room = ${if (on) "Yes" else "No"}")
                b.planView.oneFingerDraw = on
                prefs.edit().putBoolean(PREF_ONE_FINGER_DRAW, on).apply()
                updateUi()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { showSettings() }
            .show()
    }

    private fun pickWallColour(type: WallType) {
        val items = PALETTE.map { (name, c) -> swatch(c).append("  $name") as CharSequence } +
            listOf("Custom colour (#RRGGBB)…", swatch(type.defaultColor).append("  Default"))
        var reopen = true
        MaterialAlertDialogBuilder(this)
            .setTitle("${type.label} wall colour")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < PALETTE.size -> setWallColour(type, PALETTE[which].second)
                    which == PALETTE.size -> {
                        reopen = false
                        customWallColour(type)
                    }
                    else -> setWallColour(type, type.defaultColor)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (reopen) showSettings() }
            .show()
    }

    private fun customWallColour(type: WallType) {
        val input = EditText(this).apply {
            setText(String.format("#%06X", WallColors[type] and 0xFFFFFF))
            setSingleLine()
        }
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${type.label} wall colour")
            .setView(frame)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                val c = runCatching { Color.parseColor(text) }.getOrNull()
                if (c == null) Toast.makeText(this, "Not a colour: $text", Toast.LENGTH_LONG).show()
                else setWallColour(type, c or 0xFF000000.toInt())
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { showSettings() }
            .show()
    }

    private fun setWallColour(type: WallType, color: Int) {
        WallColors.set(prefs, type, color)
        AppLog.i(String.format("Setting: %s wall colour = #%06X", type.label, color and 0xFFFFFF))
        b.planView.invalidate()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Energy Plan ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Simple floor plans for energy surveys.\n\n" +
                    "Built ${BuildConfig.BUILD_DATE}\n" +
                    "github.com/${BuildConfig.GITHUB_REPO}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val PREF_ONE_FINGER_DRAW = "oneFingerDraw"
        private const val PREF_VIEW_FLOOR = "viewFloor"

        private val PALETTE = listOf(
            "Red" to 0xFFD32F2F, "Pink" to 0xFFEC407A, "Purple" to 0xFF7B1FA2, "Deep purple" to 0xFF512DA8,
            "Indigo" to 0xFF303F9F, "Blue" to 0xFF1976D2, "Light blue" to 0xFF03A9F4, "Cyan" to 0xFF00ACC1,
            "Teal" to 0xFF00897B, "Green" to 0xFF388E3C, "Light green" to 0xFF8BC34A, "Yellow" to 0xFFFBC02D,
            "Orange" to 0xFFF57C00, "Brown" to 0xFF6D4C41, "Grey" to 0xFF757575, "Black" to 0xFF212121,
        ).map { (n, c) -> n to c.toInt() }
    }
}
