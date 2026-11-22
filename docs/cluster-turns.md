# The cluster's turn display: what the car's navigation sends

How the stock navigation (`com.mnsoft.navi`, its native engine) fills
`NavigationCluster.updateTBTInfo`, worked out from its cluster code and two drives on a
US Ioniq 5 (NEEV23, 260507, 2026-11-03). The Wireless mod's Android Auto turn-by-turn
(`AaTurns`, setting `tbt_standard`) follows it. Nothing here is the car's code; it
describes what the car does.

## Two formats

- **The main display** (`updateTBTInfo`: type, side streets, direction, distance, bar):
  near a maneuver the engine sends a standard type from `Navigation.DisplayType` with
  the maneuver's angle and a distance bar. Farther away it sends the projection icon
  (the same codes as below) with the bar off, or `DIRECT_TO_DEST` (2, the destination
  compass) when no maneuver is near.
- **The HUD's turn list and the upcoming turns** (`updateTPTurnListNew`,
  `updateTPTurnList`): the projection icons, 64 and up. HMG-API's codes are the same
  as the car's: straight 65; slight right/left 66/71; right/left 67/70; sharp right/left
  68/69; U-turn 72/73; exits and ramps 81-91; roundabouts 96-107 (192-203 for
  left-hand traffic); destinations 112-114; on-ramps and forks 172-174; ferry 176.

## Near a maneuver

The engine tries, in order: destination or waypoint, highway exit, turn point, service
area, roundabout, overpass, underpass, tunnel/toll. The first that applies sets the
main display:

| Maneuver | Type (`DisplayType`) | Direction |
|---|---|---|
| straight, turn, slight, sharp | `TURN` 13 | 0; slight 45/315; turn 90/270; sharp 135/225 |
| U-turn | `U_TURN` 31 | 270 (90 for left-hand traffic) |
| on-ramp (Android Auto's ON_RAMP; the car's navigation has no ramp type, it's a turn point) | `TURN` 13 | 45 / 315 (0 with no side) |
| fork | `FORK_2` 25 | 90 / 270 |
| highway exit right / left | `EXIT_RIGHT` 17 / `EXIT_LEFT` 18 (`*_COMBI_1` 19/21 when another maneuver follows) | 90 / 270 |
| roundabout | `EXIT_ROUNDABOUT_TRS_RIGHT` 32 (33 for left-hand traffic) | the exit's direction |
| destination ahead / right / left | icon 112 / 113 / 114, no bar | 0 |
| arrived | `ARRIVED` 3 | 0 |

Angles are in 45 degree steps (the engine works in 7.5 degree units: 0, 6, 12 ... 42). Under Android Auto, `AaNavState` also decodes the raw `0x8006` `NavigationState` protobuf (`EV_DUMP`) to recover exact maneuvers that GPM's legacy callback collapses (`KEEP_LEFT`/`KEEP_RIGHT` -> `FORK_2` 25, slight vs normal off-ramps, sharp/normal/slight on-ramp angles, merge side, clockwise vs counterclockwise roundabouts, exit-number fallbacks, and EV charging-stop durations).

**The distance bar and smooth countdown** run over `GUIDE_WINDOW` (457 m / 1,500 ft): `Math.max(1, Math.min(9, m * 10 / 457)) * 10` (90 down to 10). Within `2 * GUIDE_WINDOW`, `AaTurns` and `CpTurns` interpolate remaining distance every 250 ms from observed vehicle velocity (holding steady at red lights when distance stops decreasing, and capping interpolation after 1.75 s if the phone goes quiet). Because Gen5W clusters draw the visual countdown bar from the trap frame (`NavigationCluster.updateTBTTrapInfo` / `updateTBTTrapInfoNew`, `barGraphLevel`) alongside the map speed limit, `NaviBridge` in `com.mnsoft.navi` splices the active phone `bar` into copies of stock navigation's trap frames while preserving the car's map speed limit, unit, and camera warnings.

**Side streets** (the 16-bit `sideStreet`, one bit per 22.5 degrees) are the roads the cluster draws around a `TURN` arrow. `dumpsys` prints 0 as every index "0 1 ... 15" and 65535 as "None" (its check is inverted): 65535 draws a road in every direction on `TURN` types, while 0 draws none. With enhanced turns (`tbt_standard`, on by default), `CpTurns` builds the 16-bit intersection mask from the iPhone's junction road angles (items 10 & 11), and `AaTurns` derives it from the intersection's lane arrows (`laneSideStreets`), falling back to 0 when geometry is absent and keeping 65535 on projection icons (`>= 64`).

CarPlay's turns follow the same format (`tbt_standard`, on by default); [ar-hud.md](ar-hud.md#carplay) has its mapping from iAP2 maneuvers.

## Roundabouts

Android Auto guides a roundabout in two steps: `ROUNDABOUT_ENTER_AND_EXIT` with the exit's angle before it (or standalone `ROUNDABOUT_ENTER` paired with `ROUNDABOUT_EXIT` in `0x8006`), then `ROUNDABOUT_EXIT` once the car is in it, often with no side and no angle. The Wireless mod keeps the exit icon and CW/CCW direction through `ROUNDABOUT_EXIT` until the next maneuver, falls back to exit numbers when no exit angle is sent, and strips verbose `"At the roundabout, "` prefixes so road names fit narrow cluster cards.

The exit icons are the car navigation's own, one per 45 degrees: 96 (straight on), 97, 99, 100, 102, 104, 105, 107. It never uses 103 or 106, and on a Creta Electric 106's left-hand traffic twin (0xca) draws the loading spinner, so the mod doesn't send them either. Roundabouts in the turns ahead get the same icons, with the cluster's direction rather than the raw angle.

**Which way round.** Measured on a US Ioniq 5 (issue #19), parked, by sending each code to the cluster service over adb:

- In the car's own format, `EXIT_ROUNDABOUT_TRS_RIGHT` (32) draws a counterclockwise roundabout (right-hand traffic) and `EXIT_ROUNDABOUT_TRS_LEFT` (33) a clockwise one. The exit is drawn at the direction sent, in both.
- The icons 96-107 are counterclockwise, and 192-203 (each + 96, skipping 199/202) clockwise, with the exit at the same place for the same position in the set (0xc3 is a right exit, like 99).
- 103 and 106 draw the same as 104 and 107 on this car.

Android Auto's legacy side isn't the way round: it marked a US (counterclockwise) roundabout LEFT, and 33 drew it clockwise. `AaTurns` uses `0x8006`'s explicit `_CW` vs `_CCW` maneuver types (defaulting to counterclockwise when `0x8006` is absent), and `CpTurns` uses the iAP2 driving side (`0` RHT -> 96..107 / 32, `1` LHT -> 192..203 / 33).

## Compass restoration when guidance ends

Sending `DisplayType = 0` (`NONE`) on route end triggers the Gen5W cluster and HUD's 4-bar loading animation until stock navigation crosses a `22.5°` heading boundary. Instead:

1. `AaTurns` and `CpTurns` listen to `com.hkmc.telematics.gis.action.NAVI_GPS_CHANGED`, clear road/destination/turn-list/HUD state, and emit `DisplayType = 8` (`COMPASS`) + heading sub-info quantized in counter-clockwise `22.5°` steps.
2. `NaviBridge` in `com.mnsoft.navi` captures stock navigation's own idle `DisplayType = 8` compass and sub-info frames and replays them immediately when `PHONE_TBT` reports `active = false` (or when a phone guidance lease expires), restoring the native compass instantaneously even while parked or driving straight.

## Never 64

The mod never sends 64 (0x40, "no direction"). The cluster draws its loading spinner for it, in the turn and in the HUD list, measured on a Creta Electric ([sirishant/HMG-API, `docs/creta/cluster-turn-by-turn.md`](https://github.com/sirishant/HMG-API/blob/creta-su2iev/docs/creta/cluster-turn-by-turn.md)), and on the road it stayed on through a roundabout until the next instruction. Far from a turn the cluster draws its card from the turn list, so the turns ahead matter as much as the turn itself.

- A roundabout with no exit to show, an unknown maneuver and a turn with no side: straight (65).
- A fork with no side: the plain fork icon (172).
- The turns ahead get the same icons as the turn itself: ramps and forks 172-174, exits 81/82/86, ferries 176, destinations 112-114, straight for the rest.

## Rerouting

Android Auto reports a reroute as status 3. With enhanced turns on (`tbt_standard`), a reroute still going after 1 second shows the cluster's "Recalculating..." (type 10, `RECALC ROUTE`: drawn with a spinner on a US Ioniq 5 and on a Creta Electric), until the new route's first turn (preserving any distance update that arrived during the reroute), or the turn it had when Android Auto goes back to guiding on the same route.

## Lanes

The car navigation keeps running under a phone's guidance with no route, and sends the lanes of each intersection it passes (`updateLaneInfo`, none marked). `NaviBridge` suppresses those calls while a phone guides; Android Auto's lanes and CarPlay's composite multi-arrow `0xF1` lanes (stem glyphs 9..33 for 2-arrow lanes and 47..54 for 3-arrow lanes, with 2-bit per-part highlight masks) go to the cluster alone.

## Checking on a car

`adb shell dumpsys com.hkmc.micom.cluster` prints the last values the cluster service was sent (type by name, direction, distance, bar, trap data). Once a second during a drive, with the car's navigation and then Android Auto or CarPlay on the same route, it shows any difference.
