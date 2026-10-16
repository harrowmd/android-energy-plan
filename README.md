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
- **Select:** tap a room. **Move:** drag the selected room; its edges snap to
  neighbouring rooms' edges (or the 10 cm grid).
- **Edit sizes:** double-tap a room, or select it and tap **Edit**.
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
  which makes that stretch **Internal**. **Long-press a wall** to change its
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
- **Delete:** select a room and tap **Delete**. **Undo** reverses the last
  change (up to 50 steps).
- **Pan and zoom:** use two fingers (or one finger on empty space, when
  one-finger drawing is off). **Fit** zooms to show the whole plan.
- The ceiling height you last entered is used as the default for the next room.
- The plan on screen is saved automatically after every change, so it survives
  closing the app.

## Files

**Save** (in the toolbar) asks for a plan name, then writes:

| File | Location |
|---|---|
| Plan image with room and wall schedules and a 1 m scale bar | `Pictures/EnergyPlan/<name>.jpg` |
| Plan data | `Documents/EnergyPlan/<name>.json` |
| App log (for debugging) | `Documents/EnergyPlan/<name>-log.txt` |

Saving again under the same name overwrites both files. **Share JPG** saves and
then opens the Android share sheet. **Open plan…** loads a saved `.json` file.

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
    { "id": 1, "name": "Kitchen", "x": 0, "y": 0, "width": 4.2, "depth": 3.1,
      "height": 2.4, "grossArea": 13.02, "area": 13.02, "volume": 31.248 }
  ],
  "walls": [
    { "room": "Kitchen", "side": "top", "neighbour": null, "type": "External",
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

`walls` lists every run of wall: the room it bounds, which side, the room on
the other side (`null` for none), its type, thickness and length in metres,
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

## Version history

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
