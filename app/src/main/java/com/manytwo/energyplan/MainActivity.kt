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
        b.planView.listener = this
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
        val selected = plan.room(b.planView.selectedId) != null
        menu.findItem(R.id.action_send_back).isEnabled = selected
        menu.findItem(R.id.action_bring_front).isEnabled = selected
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo -> undo()
            R.id.action_fit -> b.planView.fitToPlan()
            R.id.action_send_back -> restack { plan.sendToBack(it) }
            R.id.action_bring_front -> restack { plan.bringToFront(it) }
            R.id.action_save -> askName("Save plan", "Save") { save(share = false) }
            R.id.action_share -> save(share = true)
            R.id.action_open -> openDocument.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            R.id.action_rename -> askName("Rename plan", "Rename") {}
            R.id.action_new -> confirmNewPlan()
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
            changed()
            b.planView.select(room.id)
        }
    }

    override fun onSelectionChanged(room: Room?) {
        updateUi()
        invalidateOptionsMenu()
    }

    override fun onEditRequested(room: Room) = editRoom(room)

    override fun onBeforeMove() = pushUndo()

    override fun onRoomMoved() = changed()

    // --- rooms ---

    private fun addRoomFromKeyboard() {
        val (x, y) = b.planView.freeSpot()
        val draft = Room(plan.nextId(), "Room ${plan.rooms.size + 1}", x, y, 3.0, 3.0, lastHeight())
        showRoomDialog("Add room", draft) { room ->
            pushUndo()
            plan.rooms.add(room)
            changed()
            b.planView.select(room.id)
            b.planView.fitToPlan()
        }
    }

    private fun editRoom(room: Room) {
        showRoomDialog("Edit room", room) { edited ->
            pushUndo()
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
        changed()
    }

    private fun deleteSelected() {
        val room = plan.room(b.planView.selectedId) ?: return
        pushUndo()
        plan.rooms.remove(room)
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
            plan.rooms.isEmpty() -> "Drag on the grid to draw a room.\nTwo fingers to pan and zoom."
            else -> String.format(
                Locale.UK, "%d rooms  ·  %s m²  ·  %s m³\nTap: select · drag: move · double-tap: edit",
                plan.rooms.size, fmtArea(plan.totalArea), fmtArea(plan.totalVolume),
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
                plan.name = input.text.toString().trim().ifBlank { plan.name }
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
        thread {
            val result = runCatching {
                PlanStorage.saveJson(this, snapshot)
                PlanStorage.saveJpg(this, snapshot)
            }
            runOnUiThread {
                result.onSuccess { jpg ->
                    if (share) shareJpg(jpg)
                    else {
                        val base = PlanStorage.fileBase(snapshot.name)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Saved")
                            .setMessage("${PlanStorage.JSON_DIR}$base.json\n${PlanStorage.JPG_DIR}$base.jpg")
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

    private fun openPlan(uri: Uri) {
        try {
            val loaded = Plan.fromJson(PlanStorage.readText(this, uri))
            pushUndo()
            setPlan(loaded)
            PlanStorage.autosave(this, plan)
        } catch (e: Exception) {
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
                pushUndo()
                setPlan(Plan())
                PlanStorage.autosave(this, plan)
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
}
