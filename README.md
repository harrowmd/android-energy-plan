# Energy Plan

A simple Android app for sketching rectangular floor plans, for example during
a home energy survey. Draw rooms with your finger, type their exact sizes, and
save the plan as a JPG image plus a JSON data file.

It deliberately does a lot less than commercial floor-plan apps. Rooms are
rectangles with a width (x), depth (y) and ceiling height (z), and the app
works out each room's floor area and volume, plus the totals.

## Using it

- **Draw a room:** drag on the grid. The new room's corners snap to nearby
  walls of existing rooms (or to a 5 cm grid), so adjoining rooms line up.
  When you lift your finger, enter the room's name and exact sizes in metres.
- **Add a room from the keyboard:** tap **Add room** and type the sizes. It is
  placed to the right of the existing rooms; drag it into position.
- **Select:** tap a room. **Move:** drag the selected room; its walls snap to
  neighbouring walls.
- **Edit sizes:** double-tap a room, or select it and tap **Edit sizes**.
- **Delete:** select a room and tap **Delete**. **Undo** reverses the last
  change (up to 50 steps).
- **Pan and zoom:** use two fingers. **Fit** zooms to show the whole plan.
- The ceiling height you last entered is used as the default for the next room.
- The plan on screen is saved automatically after every change, so it survives
  closing the app.

## Files

**Save** (in the toolbar) asks for a plan name, then writes:

| File | Location |
|---|---|
| Plan image with a room schedule and 1 m scale bar | `Pictures/EnergyPlan/<name>.jpg` |
| Plan data | `Documents/EnergyPlan/<name>.json` |

Saving again under the same name overwrites both files. **Share JPG** saves and
then opens the Android share sheet. **Open plan…** loads a saved `.json` file.

The JSON format:

```json
{
  "format": "m21energyplan",
  "formatVersion": 1,
  "name": "12 Acacia Avenue",
  "units": "m",
  "saved": "2026-10-15T18:20:00+01:00",
  "totalArea": 27.5,
  "totalVolume": 66.0,
  "rooms": [
    { "name": "Kitchen", "x": 0, "y": 0, "width": 4.2, "depth": 3.1,
      "height": 2.4, "area": 13.02, "volume": 31.248 }
  ]
}
```

`x`/`y` is the room's top-left corner on the plan, in metres. `area` and
`volume` are included for convenience and recalculated on load.

## Building from source

```bash
git clone https://github.com/harrowmd/android-energy-plan.git
cd android-energy-plan
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/m21energyplan.apk
```

Requires the Android SDK (platform 36) and JDK 17. Runs on Android 10 or later.

## Version history

- **v1.0** — First release: draw, move, edit and delete rectangular rooms;
  JPG and JSON export; undo; autosave.
