# Energy Plan

A simple Android app for sketching rectangular floor plans, for example during
a home energy survey. Draw rooms with your finger, type their exact sizes, mark
each wall's type, and save the plan as a JPG image plus a JSON data file.

It deliberately does a lot less than commercial floor-plan apps. Rooms are
rectangles with a width (x), depth (y) and ceiling height (z), and the app
works out each room's floor area and volume, plus the totals.

## Using it

- **Add a room:** tap **+ Room** and type its name and exact sizes in metres.
  It is placed to the right of the existing rooms; drag it into position.
- **Draw a room with one finger (optional):** turn on **⋮ → Settings… → One
  finger create room: Yes**, then drag on the grid. The new room's corners snap
  to nearby edges of existing rooms (or to a 10 cm grid), and when you lift your
  finger you enter the exact sizes. It's off by default because it's easy to
  create rooms by accident; with it off, a one-finger drag pans the plan.
- **Select:** long-press a room (a short tap doesn't select, so it can't happen by accident; a tap on empty space clears the selection). **Move:** keep holding and drag, or drag the selected room; its edges snap to
  neighbouring rooms' edges (or the 10 cm grid).
- **Edit sizes:** double-tap inside a room, or select it and tap **Edit**.
- **Floors:** every room is on a floor: **Basement**, **Ground** or **1st**–
  **7th**. The screen shows one floor at a time; tap the floor name in the
  toolbar to switch. The nearest other floor is drawn as faint dashed outlines
  to line rooms up against (room edges snap to it too). New rooms go on the
  floor shown; change a room's floor in its dialog. Rooms on different floors
  never overlap or share walls. The JPG draws each floor separately.
- **Add floor:** select any room and use **⋮ → Add floor**: every room in
  that room's building part on that floor is copied to the floor above, same
  sizes and positions (and any wall changes between them). Rename and resize
  them there. One Undo removes the whole copy. From the ground floor,
  **⋮ → Add basement** does the same, copying the part down into the Basement.
- **Building part:** every room belongs to **Main** or an extension,
  **Ext1**–**Ext4**, or a **Conservatory**, chosen with the buttons in the room dialog. New
  rooms start in the part you used last. Rooms outside Main show their part
  on the plan, and the JPG and JSON give totals for each part.
- **Overlapping rooms:** a room drawn later sits on top. Where rooms overlap,
  the overlap belongs to the room on top: the room underneath shows (and
  exports) its area and volume minus the covered part, and the totals don't
  count it twice. Select a room and use the ⋮ menu to change which one is on
  top: **Bring forward** / **Send backward** move it one step past the next
  room it overlaps (rooms it doesn't touch are skipped), and **Bring to front**
  / **Send to back** move it all the way. E.g. draw a large room, then a WC
  inside it, and the large room's area excludes the WC.
- **Walls:** sizes you type are internal (inside the walls). Rooms that share
  a wall are drawn touching, and the wall is drawn centred on the join, half
  in each room. Walls with nothing beyond them wrap around the outside. Every
  wall starts as **External**, unless another room touches it (within 15 cm),
  which makes that stretch **Internal**. **Double-tap a wall** to change its
  type or thickness; **Automatic** puts it back.

  | Type | Default thickness | Default colour |
  |---|---|---|
  | External | 350 mm | Red |
  | Internal | 120 mm | Green |
  | Sheltered | 150 mm | Purple |
  | Party | 300 mm | Orange |
  | Stud | 100 mm | Blue |
  | Glazing | 80 mm | Pink |

  The colour for each type can be changed in **⋮ → Settings…**. All the
  outside stretches on one side of a room share one setting.
- **Summary:** **⋮ → Summary** shows, for every building part on every
  floor, the area, average height, **HLP** (heat loss perimeter: the length of
  External, Sheltered and Glazing walls) and **PWL** (party wall length), with
  a subtotal per part and a grand total. The JPG has the same table under each
  floor's drawing and a Summary section after the last floor. Unallocated
  space adds no area, but its outside walls still count towards HLP and PWL
  (for its part and floor); a wall between it and a room counts for the room.
- **Bay windows:** **long-press an outside wall** to add a bay there: choose
  **Angled** (45° sides), **Square** or **Bow** (a circular arc), its depth out
  from the wall and its width along it. It's centred where you pressed, within
  that stretch of wall. The bay's floor adds to the room's area and volume, and
  the wall follows its outline (same type and thickness), so the wall - and
  HLP or PWL - gets longer by the bay's outline less its width. Long-press a
  bay to change or remove it. Bays belong to their floor: **Add floor** and
  **Add basement** don't copy them.
- **Delete:** select a room and tap **Delete**, then choose **Delete
  completely** (the room and its walls go) or **Make unallocated**: the space
  and its outside walls stay exactly as they are (there's no wall between
  two unallocated spaces, but rooms next to one keep theirs). It's shown as
  plain grey hatching and left out of all area and volume totals. Delete on
  an unallocated space removes it completely or restores the room. **Undo** reverses the last
  change (up to 50 steps).
- **Pan and zoom:** use two fingers (or one finger on empty space, when
  one-finger drawing is off). **Fit** zooms to show the whole plan.
- The ceiling height you last entered is used as the default for the next room.
- The plan on screen is saved automatically after every change, so it survives
  closing the app.

## Files

**Save** (in the toolbar) asks for a plan name, then writes these files,
named with the save date and the plan name (spaces become `_`):

| File | Contents |
|---|---|
| `2026-10-17-Test_Plan_2.Ground.jpg` (one per floor: `.Basement`, `.Ground`, `.1st` …) | That floor's drawing, 1 m scale bar and dimensions table, on an A4 page |
| `2026-10-17-Test_Plan_2.Summary.jpg` (`.Summary-2.jpg` … if longer) | Dimensions summary, wall schedule and room schedule, on A4 pages |
| `2026-10-17-Test_Plan_2.pdf` | All of the pages above, at A4 |
| `2026-10-17-Test_Plan_2.json` | Plan data |
| `2026-10-17-Test_Plan_2.txt` | App log (for debugging) |

By default the JPGs go to `Pictures/EnergyPlan/` and the rest to
`Documents/EnergyPlan/`. **⋮ → Save location…** picks any other folder
(including a cloud folder whose app offers one), or goes back to the default.
Saving again on the same day overwrites that day's files (and removes page
JPGs the new save no longer needs); a save on another day makes a new set.
**Open plan…** loads a saved `.json` file. **⋮ → Share log** sends the log.

The log records app starts (version, device, Android version), every room
drawn, added, edited, moved, deleted or restacked, undo, saves and opens, and
any errors or crashes with their stack traces. It is kept privately by the app
(capped at about 0.5 MB, oldest lines dropped first), copied out on every save
— even a failed one — and can be sent from **⋮ → Share log**. The same lines go
to logcat under the tag `EnergyPlan`.

The JSON format:

```json
{
  "format": "m21energyplan",
  "formatVersion": 2,
  "name": "12 Acacia Avenue",
  "units": "m",
  "saved": "2026-10-15T18:20:00+01:00",
  "totalArea": 27.5,
  "totalVolume": 66.0,
  "rooms": [
    { "id": 1, "name": "Kitchen", "floor": "Ground", "buildingPart": "Main", "x": 0, "y": 0, "width": 4.2, "depth": 3.1,
      "height": 2.4, "grossArea": 13.02, "area": 13.02, "volume": 31.248 }
  ],
  "floorTotals": [ { "floor": "Ground", "rooms": 1, "area": 13.02, "volume": 31.248 } ],
  "dimensions": [ { "buildingPart": "Main", "floor": "Ground", "rooms": 1,
    "area": 13.02, "height": 2.4, "hlp": 14.6, "pwl": 0 } ],
  "dimensionsTotal": { "rooms": 1, "area": 13.02, "height": 2.4, "hlp": 14.6, "pwl": 0 },
  "partTotals": [ { "buildingPart": "Main", "rooms": 1, "area": 13.02, "volume": 31.248 } ],
  "walls": [
    { "floor": "Ground", "roomId": 1, "room": "Kitchen", "side": "top",
      "neighbourId": null, "neighbour": null, "type": "External",
      "thickness": 0.35, "length": 4.2, "x1": 0, "y1": 0, "x2": 4.2, "y2": 0 }
  ],
  "wallTotals": [ { "type": "External", "thickness": 0.35, "length": 14.6 } ],
  "wallOverrides": [ { "key": "1:RIGHT", "type": "Party", "thickness": 0.3 } ]
}
```

`x`/`y` is the room's top-left corner on the plan, in metres. Rooms are listed
in stacking order (bottom first). `grossArea` is width × depth; `area` and
`volume` exclude any part covered by rooms higher in the list. All three are
included for convenience and recalculated on load.

`unallocated` is `true` for space kept from a deleted room: it shapes the
walls as before but isn't counted in any total (`unallocatedArea` gives its
area). `floor` is `Basement`, `Ground`, `1st` … `7th` (rooms in older files open on
`Ground`); `floorTotals` gives the rooms, net area and volume of each floor in
use. `buildingPart` is `Main`, `Ext1`, `Ext2`, `Ext3`, `Ext4` or `Conservatory` (rooms in older
files open as `Main`); `partTotals` gives the number of rooms and the net area
and volume of each part in use.

`dimensions` has one entry per building part and floor: net area, average
height (weighted by area), `hlp` (heat loss perimeter: External, Sheltered and
Glazing walls) and `pwl` (party wall length), in metres; `dimensionsTotal`
adds them up.

A room's `bays` (when it has any) give each bay's `type` (Angled, Square,
Bow), `side`, `offset` (from the left/top end of that side), `width`, `depth`,
`area` and `perimeter`; the room's areas and volume include them, and the
wall's `length` includes the extra round the bay.

`walls` lists every run of wall: its floor, the room it bounds (id and name),
which side, the room on the other side (id and name, `null` for none), its type, thickness and length in metres,
and its end points along the room's inside face. A wall shared by two rooms is
listed once. `wallTotals` adds up the lengths per type and thickness.
`wallOverrides` holds the walls you changed, keyed by room `id`; it's the only
wall data read back on load. Version 1 files (no ids, no walls) still open.

## Building from source

```bash
git clone https://github.com/harrowmd/android-energy-plan.git
cd android-energy-plan
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/m21energyplan.apk
```

Requires the Android SDK (platform 36) and JDK 17. Runs on Android 10 or later.

## Updating

**⋮ → About** has **Check for update**, which looks up the latest GitHub
release and, if it's newer, downloads `m21energyplan.apk` and opens Android's
installer; and **Reinstall** (this version), which fetches this version's
release APK again. The first time, Android asks you to allow Energy Plan to
install apps. The plan on screen and the settings are kept.

## Version history

- **v1.8** — Bay windows (Angled, Square, Bow): long-press an outside wall;
  they add area and wall length (HLP/PWL). Double-tap a wall for its type.
  About → Check for update / Reinstall from GitHub. One save at a time;
  faster saves to cloud folders.

- **v1.7.3** — Notes on the PDF/JPG pages wrap instead of running off the page.

- **v1.7.2** — HLP and PWL include the outside walls of unallocated space
  (it still adds no area).

- **v1.7.1** — When the floor button doesn't fit in the toolbar (phones), its
  ⋮ menu entry reads "Floor: Ground floor…" instead of just the floor name.

- **v1.7** — Dimensions tables: area, height, HLP and PWL per building part
  and floor, under each floor in the JPG, in a Summary section and screen
  (**⋮ → Summary**), and in the JSON. JPG column fixes; wall log lines name
  the floor. Save writes one A4 JPG per floor plus summary pages, a PDF of
  them all, the JSON and the log, named yyyy-mm-dd-Plan_Name; **Save
  location…** chooses the folder; Share JPG removed.
- **v1.6** — Floors (Basement, Ground, 1st–7th), shown one at a time with
  the floor below as dashed outlines; **Add floor** / **Add basement** copy a
  building part to another floor. Building parts (Main, Ext1–Ext4,
  Conservatory). Totals per floor and per part in the JPG and JSON. Delete can
  leave a room's space as unallocated (hatched, not counted). Rooms are
  selected with a long-press. Wall entries in the JSON carry floor and room ids.
- **v1.5** — Wall types: External, Internal, Sheltered, Party, Stud and
  Glazing, each with a default thickness and a colour you can change in
  Settings. Long-press a wall to change it. Walls are listed in the JSON and
  the JPG. Grid snap is now 10 cm.
- **v1.4.2** — A drag that snaps a room back to where it started no longer
  adds an Undo step or a "Moved" log line.
- **v1.4.1** — The toolbar grows to fit the plan name, which was cut off on
  tablets with larger toolbar text.
- **v1.4** — **Bring forward** / **Send backward**: one step at a time, past
  the next overlapping room. Stacking menu items grey out when they'd do nothing.
- **v1.3** — One-finger drawing is now a setting (**⋮ → Settings…**), off by
  default: a one-finger drag pans instead of creating a room. Fixed the ⋮ menu's
  white-on-light text.
- **v1.2** — App log: saved as `<name>-log.txt` next to the JSON, **Share
  log** menu item, and crashes are recorded with their stack trace.
- **v1.1** — Overlapping rooms: **Send to back** / **Bring to front**, and the
  room underneath loses the overlapped area and volume (marked * in the JPG's
  room schedule). Labels move to the uncovered part of a room.
- **v1.0** — First release: draw, move, edit and delete rectangular rooms;
  JPG and JSON export; undo; autosave.
