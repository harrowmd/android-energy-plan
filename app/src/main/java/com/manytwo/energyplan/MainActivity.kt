package com.manytwo.energyplan

import android.content.Intent
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

        plan = PlanStorage.loadAutosave(this) ?: Plan()
        AppLog.i("Opened plan '${plan.name}' with ${plan.rooms.size} rooms, ${fmtM(plan.totalArea)} m²")
        b.planView.listener = this
        b.planView.oneFingerDraw = prefs.getBoolean(PREF_ONE_FINGER_DRAW, false)
        b.planView.plan = plan

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
        menu.findItem(R.id.action_undo).isEnabled = undoStack.isNotEmpty()
        // Each stacking action is only offered when it would change something.
        val room = plan.room(b.planView.selectedId)
        val up = room != null && plan.canBringForward(room)
        val down = room != null && plan.canSendBackward(room)
        menu.findItem(R.id.action_bring_forward).isEnabled = up
        menu.findItem(R.id.action_bring_front).isEnabled = up
        menu.findItem(R.id.action_send_backward).isEnabled = down
        menu.findItem(R.id.action_send_back).isEnabled = down
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo -> undo()
            R.id.action_fit -> b.planView.fitToPlan()
            R.id.action_bring_forward -> restack { plan.bringForward(it) }
            R.id.action_bring_front -> restack { plan.bringToFront(it) }
            R.id.action_send_backward -> restack { plan.sendBackward(it) }
            R.id.action_send_back -> restack { plan.sendToBack(it) }
            R.id.action_save -> askName("Save plan", "Save") { save(share = false) }
            R.id.action_share -> save(share = true)
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
        val draft = Room(plan.nextId(), "Room ${plan.rooms.size + 1}", x, y, width, depth, lastHeight())
        showRoomDialog("New room", draft, onDismiss = { b.planView.clearDraft() }) { room ->
            pushUndo()
            plan.rooms.add(room)
            AppLog.i("Drew room ${room.describe()}")
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

    // --- rooms ---

    private fun addRoomFromKeyboard() {
        val (x, y) = b.planView.freeSpot()
        val draft = Room(plan.nextId(), "Room ${plan.rooms.size + 1}", x, y, 3.0, 3.0, lastHeight())
        showRoomDialog("Add room", draft) { room ->
            pushUndo()
            plan.rooms.add(room)
            AppLog.i("Added room from keyboard ${room.describe()}")
            changed()
            b.planView.select(room.id)
            b.planView.fitToPlan()
        }
    }

    private fun editRoom(room: Room) {
        showRoomDialog("Edit room", room) { edited ->
            pushUndo()
            AppLog.i("Edited room ${room.describe()} -> ${edited.describe()}")
            // Undo snapshot holds copies, so it's safe to change the live room in place.
            room.name = edited.name
            room.width = edited.width
            room.depth = edited.depth
            room.height = edited.height
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

    private fun deleteSelected() {
        val room = plan.room(b.planView.selectedId) ?: return
        pushUndo()
        plan.rooms.remove(room)
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
                prefs.edit().putFloat("lastHeight", h.toFloat()).apply()
                onOk(initial.copy(name = name, width = w, depth = dd, height = h))
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
        if (fit) b.planView.fitToPlan()
        updateUi()
    }

    private fun updateUi() {
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = plan.name
        val room = plan.room(b.planView.selectedId)
        b.status.text = when {
            room != null -> {
                val net = plan.netArea(room)
                val covered = round3(room.area) - net
                val heading = if (covered > 0) String.format(Locale.UK, "%s  ·  %s m² covered by rooms on top", room.name, fmtM(covered)) else room.name
                String.format(
                    Locale.UK, "%s\n%s × %s × %s m  ·  %s m²  ·  %s m³",
                    heading, fmtM(room.width), fmtM(room.depth), fmtM(room.height), fmtM(net), fmtM(plan.netVolume(room)),
                )
            }
            plan.rooms.isEmpty() && b.planView.oneFingerDraw -> "Drag on the grid to draw a room.\nTwo fingers to pan and zoom."
            plan.rooms.isEmpty() -> "Tap + Room to add a room.\nDrag to pan · pinch to zoom."
            else -> String.format(
                Locale.UK, "%d rooms  ·  %s m²  ·  %s m³\n%s",
                plan.rooms.size, fmtArea(plan.totalArea), fmtArea(plan.totalVolume),
                if (b.planView.oneFingerDraw) "Drag: draw · tap: select · drag selected: move" else "Drag: pan · tap: select · drag selected: move",
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

    private fun save(share: Boolean) {
        val snapshot = plan.copy()
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        AppLog.i("Saving '${snapshot.name}': ${snapshot.rooms.size} rooms, ${fmtM(snapshot.totalArea)} m², ${fmtM(snapshot.totalVolume)} m³" + if (share) " (then share)" else "")
        thread {
            val start = System.currentTimeMillis()
            val result = runCatching {
                PlanStorage.saveJson(this, snapshot).also { AppLog.i("Wrote JSON $it") }
                PlanStorage.saveJpg(this, snapshot).also { AppLog.i("Wrote JPG $it") }
            }
            result.onSuccess { AppLog.i("Save finished in ${System.currentTimeMillis() - start} ms") }
                .onFailure { AppLog.e("Save failed", it) }
            // Written last, so it records how the save went - even a failed one.
            runCatching { PlanStorage.saveLog(this, snapshot) }.onFailure { AppLog.e("Could not write log file", it) }
            runOnUiThread {
                result.onSuccess { jpg ->
                    if (share) shareJpg(jpg)
                    else {
                        val base = PlanStorage.fileBase(snapshot.name)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Saved")
                            .setMessage("${PlanStorage.JSON_DIR}$base.json\n${PlanStorage.JSON_DIR}$base-log.txt\n${PlanStorage.JPG_DIR}$base.jpg")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Share JPG") { _, _ -> shareJpg(jpg) }
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(this, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareJpg(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, plan.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share plan"))
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

    private fun showSettings() {
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
            .show()
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
    }
}
