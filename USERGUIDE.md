# Energy Plan – User Guide

Energy Plan sketches simple floor plans for home energy surveys. You draw
rectangular rooms, type their exact sizes, mark what each wall is, and save
the plan as A4 pages (JPG and PDF) plus a data file (JSON) with every area,
volume and wall length worked out for you.

This guide covers version 1.8.1 and later. The latest copy is on GitHub:
<https://github.com/harrowmd/android-energy-plan/blob/main/USERGUIDE.md>

## Contents

1. [The screen](#1-the-screen)
2. [Touch gestures](#2-touch-gestures)
3. [Rooms](#3-rooms)
4. [Walls](#4-walls)
5. [Bay windows](#5-bay-windows)
6. [Floors](#6-floors)
7. [Building parts](#7-building-parts)
8. [Overlapping rooms](#8-overlapping-rooms)
9. [Unallocated space](#9-unallocated-space)
10. [Summary: area, height, HLP and PWL](#10-summary-area-height-hlp-and-pwl)
11. [Saving, files and opening plans](#11-saving-files-and-opening-plans)
12. [Undo, autosave and new plans](#12-undo-autosave-and-new-plans)
13. [Settings](#13-settings)
14. [Updating the app](#14-updating-the-app)
15. [The ⋮ menu at a glance](#15-the--menu-at-a-glance)
16. [Tips and troubleshooting](#16-tips-and-troubleshooting)
17. [Data file (JSON) reference](#17-data-file-json-reference)

---

## 1. The screen

- **Toolbar (top):** the plan name and the floor you're looking at, then
    - **Floor button** (e.g. *GROUND*) – switch floors. On a narrow phone it
      moves into the ⋮ menu as **Floor: Ground floor…**
    - **Undo** – take back the last change.
    - **Fit** – zoom so the whole floor fits the screen.
    - **Save** – save the plan (see [Saving](#11-saving-files-and-opening-plans)).
    - **⋮** – the menu with everything else.
- **Plan (middle):** a grid with 50 cm squares (shown bottom-left, e.g.
  *Grid 50 cm · snap 10 cm*). Rooms are pale green; walls are solid bars in
  their type's colour; the floor below shows as faint dashed outlines.
- **Status (bottom):** with nothing selected, the number of rooms, area and
  volume of the floor shown, and a reminder of the gestures. With a room
  selected, its name, building part, sizes, area and volume.
- **Buttons (bottom):** **+ Room** (add a room by typing its sizes),
  **Edit** and **Delete** (for the selected room).

## 2. Touch gestures

| Gesture | Where | What it does |
|---|---|---|
| Drag one finger | Anywhere | Pans the plan (or draws a room, if *One finger create room* is on) |
| Pinch / two fingers | Anywhere | Zooms and pans |
| Long-press | Inside a room | Selects it (top room where rooms overlap); keep holding and drag to move it |
| Drag | The selected room | Moves it; edges snap to other rooms and the 10 cm grid |
| Tap | Empty space | Clears the selection |
| Double-tap | Inside a room | Opens the room's dialog (name, floor, part, sizes) |
| Double-tap | On a wall | Opens the wall dialog (type and thickness) |
| Long-press | On an outside wall | Adds a bay window there |
| Long-press | On a bay window | Changes or removes it |

A short tap on a room does **not** select it, so rooms aren't picked by
accident. A short vibration tells you a long-press has worked.

## 3. Rooms

### Adding a room

- **+ Room:** type a name, choose its floor and building part, and enter
    - **Width – x** (left to right, metres),
    - **Depth – y** (top to bottom, metres),
    - **Ceiling height – z** (metres).

    It's placed to the right of the rooms already on that floor, touching
    them. Drag it into place.

- **Draw with one finger (optional):** turn on **⋮ → Settings… → One finger
    create room**, then drag on the grid. Corners snap to nearby room edges or
    the 10 cm grid; when you lift your finger, the room dialog opens with the
    drawn size filled in.

Sizes are **internal** (inside the walls) – measure wall face to wall face.
The ceiling height you last typed is used for the next new room, and new
rooms start on the floor shown and in the building part you used last.

### Moving, editing and deleting

- **Move:** long-press the room and drag, or drag it once selected. Edges
  snap to the edges of other rooms (on any floor) and to a 10 cm grid, so
  neighbouring rooms line up exactly.
- **Edit:** double-tap the room, or select it and tap **Edit**. You can
  change its name, floor, building part and sizes. Changing the floor moves
  the view to that floor.
- **Delete:** select it and tap **Delete**, then choose
    - **Delete completely** – the room and its walls go; or
    - **Make unallocated** – keep the space and its outside walls, but it's no
      longer a room (see [Unallocated space](#9-unallocated-space)).

### What's shown in a room

The room's name, its net floor area, its height (and its building part if
it isn't *Main*), with the width along the top wall and the depth along the
left wall. Labels move to the visible part of a room if another room covers
part of it, and keep clear of thick walls.

## 4. Walls

### Automatic walls

Every side of every room gets a wall automatically:

- Where another room on the same floor touches it (or is within 15 cm), that
  stretch is a shared **Internal** wall, drawn **centred on the join** – half
  in each room – so the rooms stay neatly aligned.
- Everything else is **External** and is drawn **outside** the room, wrapping
  round it, with square corners.

Draw rooms touching each other – snapping makes this easy – and the walls
take care of themselves.

### Wall types

| Type | Default thickness | Default colour | Typical use |
|---|---|---|---|
| External | 350 mm | Red | Outside walls |
| Internal | 120 mm | Green | Walls between rooms |
| Sheltered | 150 mm | Purple | Walls to an unheated space (garage, loft, store) |
| Party | 300 mm | Orange | Walls shared with a neighbouring home |
| Stud | 100 mm | Blue | Timber stud partitions |
| Glazing | 80 mm | Pink | Fully glazed walls (e.g. conservatories) |

### Changing a wall

**Double-tap a wall.** The wall is outlined and a dialog shows where it is
(e.g. *Between Kitchen and Dining · 3.50 m long*) and its automatic type.

- Pick a **type** – its default thickness is filled in.
- Change the **Thickness (mm)** if the wall is thicker or thinner.
- **OK** saves it; **Automatic** puts the wall back to its automatic type.

A wall is split into separate pieces wherever the room on the other side
changes, and each piece keeps its own setting. On an outside wall, all the
outside stretches on one side of a room share one setting.

Wall colours can be changed in **⋮ → Settings…**.

## 5. Bay windows

**Long-press an outside wall** where you want the bay. Choose:

- **Type:**
    - **Angled** – a front parallel to the wall with 45° sides (steeper if the
      bay is too deep for 45°).
    - **Square** – a rectangular box.
    - **Bow** – a circular arc.
- **Depth out from the wall** (metres).
- **Width along the wall** (metres) – the dialog shows the most that fits on
  that stretch of wall.

The bay is centred where you pressed, within that stretch of wall; bays on
the same wall can't overlap.

What a bay does:

- Its **floor area is added to the room** (and the room's volume).
- The **wall follows the bay's outline**, with the same type and thickness as
  the wall it's on. That wall gets longer by the bay's outline less its
  width, which increases the HLP (or PWL for a party wall).
- Bays belong to their floor: **Add floor** and **Add basement** don't copy
  them.

**Long-press a bay** to change it, or tap **Remove** in its dialog.

In the room schedule, rooms with bays are marked **†**, because their area
is more than width × depth.

## 6. Floors

Each room is on a floor: **Basement, Ground, 1st … 7th**.

- **Switch floors:** tap the floor button in the toolbar (or **⋮ → Floor:…**
  on a phone). The list shows how many rooms each floor has. Switching keeps
  the same zoom and position, so floors line up as you flip between them.
- **Guide outlines:** the nearest other floor with rooms (the one below,
  where there is one) is drawn as faint dashed outlines, so you can line up
  the rooms above. Room edges snap to them too.
- **New rooms** go on the floor shown. To move a room to another floor,
  change **Floor** in its dialog.
- Rooms on different floors never overlap or share walls.

### Add floor and Add basement

To copy a whole floor's layout as a starting point:

1. Long-press any room in the building part you want to copy (e.g. *Main*).
2. **⋮ → Add floor: copy Main to 1st floor**.
3. Confirm. Every room in that building part on that floor is copied to the
   floor above, with the same sizes, positions and wall changes (but not bay
   windows). The view moves to the new floor.
4. Rename, resize, delete or make rooms unallocated as needed.

From the ground floor, **⋮ → Add basement** does the same downwards. One
**Undo** removes a whole copy. If the target floor already has rooms in that
part, you're warned first; the copies are added on top.

## 7. Building parts

Each room belongs to a building part: **Main**, **Ext1** to **Ext4**
(extensions) or **Conservatory**. Choose it in the room dialog. Rooms
outside Main show their part on the plan (e.g. *h 2.40 m · Ext1*).

Areas, volumes, HLP and PWL are given for each part and floor in the
[Summary](#10-summary-area-height-hlp-and-pwl).

## 8. Overlapping rooms

A room drawn later sits **on top** of earlier ones. Where rooms overlap, the
overlap belongs to the room on top: the room underneath loses that area
(and volume), and the totals don't count it twice. For example, draw a large
room and then a WC inside it – the large room's area excludes the WC.

Change which room is on top from the ⋮ menu (select a room first):

- **Bring forward / Send backward** – one step past the next room it
  overlaps (rooms it doesn't touch are skipped).
- **Bring to front / Send to back** – all the way.

Items that would change nothing are greyed out. In the room schedule, rooms
that lose area to a room on top are marked **\***.

## 9. Unallocated space

**Delete → Make unallocated** turns a room into unallocated space – for
example a loft void, stairwell, or the parts of a floor copied with **Add
floor** that don't exist upstairs.

- It's shown as **grey hatching** only (no name, sizes or outline). The
  floor below shows through as dashed outlines.
- It adds **no area or volume** to any total.
- Its **outside walls stay** – they're still the building's envelope and
  **count towards HLP and PWL** (for its building part and floor).
- There's no wall between two unallocated spaces. A room next to unallocated
  space keeps its wall (Internal by default – change it to *Sheltered* if the
  space is unheated).
- Select it and tap **Delete** again to **Delete completely** or **Restore as
  room** (it gets its name back).

## 10. Summary: area, height, HLP and PWL

**⋮ → Summary** shows a table for the whole plan:

| Column | Meaning |
|---|---|
| Building part, Floor | One row per building part on each floor |
| Area m² | Net floor area of the rooms (including bays, excluding unallocated space and parts covered by rooms on top) |
| Height m | Average ceiling height, weighted by area |
| HLP m | **Heat loss perimeter**: total length of External, Sheltered and Glazing walls |
| PWL m | **Party wall length**: total length of Party walls |

A part on more than one floor gets a subtotal row, and the last row is the
total. All wall lengths are measured along the **inside face** of the rooms.

The same table appears in the saved files: under each floor's drawing (that
floor's rows) and as the **Summary** page (with subtotals).

## 11. Saving, files and opening plans

### Save

Tap **Save**, check the plan name, and tap **Save**. The app writes,
named with today's date and the plan name (spaces become `_`):

| File | What's in it |
|---|---|
| `2026-10-18-My_House.Ground.jpg` – one per floor (`.Basement`, `.Ground`, `.1st`, …) | That floor on an A4 page: the drawing, a 1 m scale bar and the floor's dimensions table |
| `2026-10-18-My_House.Summary.jpg` (and `.Summary-2.jpg` … if longer) | The summary (dimensions with subtotals), the wall schedule (total length of each wall type and thickness) and the room schedule (every room with floor, part, sizes, area and volume, plus totals per floor and per part) |
| `2026-10-18-My_House.pdf` | All of the pages above, at A4 – ready to print or email |
| `2026-10-18-My_House.json` | The plan data, to open again later or use elsewhere |
| `2026-10-18-My_House.txt` | The app's log, which helps if something goes wrong |

- Floor pages are landscape if the building is wider than it is deep. All
  floors use the same scale and position, so they line up page to page.
- Saving again **on the same day** replaces that day's files (and removes
  page JPGs no longer needed, e.g. a floor you deleted). Saving on another
  day makes a new set, so earlier days are kept.
- Only one save runs at a time; pressing Save during a save shows *Still
  saving…*.

### Where files go – Save location

By default, JPGs go to **Pictures/EnergyPlan** (they appear in the gallery)
and everything else to **Documents/EnergyPlan**.

**⋮ → Save location… → Choose folder…** sends all files to a folder you
pick instead – including a cloud folder such as Nextcloud, if its app offers
one in the picker. **Use default** goes back to the default folders. Saving
to a cloud folder is slower (each file is uploaded; allow 20–40 seconds).

### Open a saved plan

**⋮ → Open plan…** and pick a saved `.json` file. The current plan can be
brought back with **Undo**.

### Rename

**⋮ → Rename plan…** changes the plan name (used in the file names and on
the pages).

### Share log

**⋮ → Share log** sends the app's log (e.g. by email) – useful when
reporting a problem.

## 12. Undo, autosave and new plans

- **Undo** reverses the last change – adding, editing, moving, deleting,
  restacking, wall and bay changes, copying floors – up to 50 steps.
- The plan on screen is **saved automatically** after every change, so
  closing the app (or updating it) loses nothing. Autosave is separate from
  **Save**: it doesn't write the JPG/PDF/JSON files.
- **⋮ → New plan** starts an empty plan after asking you to confirm. The old
  one can still be brought back with Undo (or opened from its saved JSON).

## 13. Settings

**⋮ → Settings…**:

- **One finger create room** – *No* (default): one finger pans the plan.
  *Yes*: one-finger drag draws a new room. It's off by default because it's
  easy to create rooms by accident.
- **Wall colours** – one entry per wall type. Pick from 16 colours, enter a
  custom **#RRGGBB** colour, or go back to the **Default**. Colours are used
  on screen and in the saved pages. They're a setting on each device, not
  part of the plan.

## 14. Updating the app

**⋮ → About** shows the version and build date, with:

- **Check for update** – asks GitHub for the latest release. If it's newer,
  you see what's new and can **Download and install** it.
- **Reinstall** – downloads this same version again from GitHub and installs
  it over the app (useful if something seems wrong with the installed copy).

The first time, Android asks you to **allow Energy Plan to install apps**
(*Install unknown apps → Allow from this source*). Turn it on, come back to
the app and tap the button again; Android's installer then asks you to
confirm. The plan on screen and your settings are kept.

## 15. The ⋮ menu at a glance

| Item | What it does |
|---|---|
| Floor: … | Switch floor (only here when it doesn't fit in the toolbar) |
| Summary | Area, height, HLP and PWL for every part and floor |
| Add floor | Copy the selected room's building part to the floor above |
| Add basement | Copy the selected ground-floor room's part to the basement |
| Bring forward / to front | Put the selected room above overlapping rooms |
| Send backward / to back | Put the selected room below overlapping rooms |
| Save location… | Choose the folder for saved files |
| Share log | Send the app log |
| Open plan… | Open a saved `.json` plan |
| Rename plan… | Change the plan name |
| New plan | Start an empty plan |
| Settings… | One-finger drawing and wall colours |
| Help | This guide |
| About | Version, Check for update, Reinstall |

## 16. Tips and troubleshooting

- **Work room by room, touching:** add a room, drag it against its neighbour
  until it snaps, then the next. Shared walls appear automatically.
- **Copy floors:** draw the ground floor completely, then use **Add floor**
  and adjust, rather than drawing upper floors from scratch.
- **Unheated spaces:** make a room *unallocated* (or leave it out), then set
  the walls of the heated rooms facing it to **Sheltered**.
- **A room won't select:** use a long-press, not a tap. Where rooms overlap
  the top one is selected; use **Send backward** to reach one underneath.
- **A wall dialog opens instead of the room's:** you double-tapped close to
  a wall; double-tap nearer the middle of the room.
- **Long-press on a wall between rooms says "Bay windows go on outside
  walls":** bays can only be added to outside walls.
- **Saving to a cloud folder is slow:** that's the upload; wait for the
  *Saved* message. Use the default folders for speed.
- **Something looks wrong:** **⋮ → Share log** and send the log with a
  description of what happened.

## 17. Data file (JSON) reference

The `.json` file holds everything needed to reopen the plan, plus the
calculated results:

- `name`, `saved`, `units` (`m`), `formatVersion`.
- `rooms` – in stacking order (bottom first). Each has `id`, `name`,
  `floor`, `buildingPart`, `unallocated`, `x`, `y` (top-left corner),
  `width`, `depth`, `height`, `grossArea`, `area` (net), `volume`, and
  `bays` (type, side, offset, width, depth, area, perimeter) if it has any.
- `walls` – every stretch of wall: `floor`, `roomId`, `room`, `side`,
  `neighbourId`, `neighbour` (`null` for outside walls), `type`,
  `thickness`, `length` (including bays) and its end points.
- `wallTotals` – total length per wall type and thickness.
- `dimensions` and `dimensionsTotal` – the Summary table: area, height, HLP
  and PWL per building part and floor.
- `floorTotals`, `partTotals`, `totalArea`, `totalVolume`,
  `unallocatedArea`.
- `wallOverrides` – the walls you changed (the only wall data read back when
  opening a plan; everything else is recalculated).

All lengths are in metres and areas in m².
