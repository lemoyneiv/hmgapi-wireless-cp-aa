# The AR HUD and the speed limit under Android Auto and CarPlay

How the Wireless mod drives the AR HUD's animated turn scenes and fills the cluster's
visual countdown bar without losing the car's speed-limit sign. Worked out on a US
Ioniq 5 with the AR HUD (NEEV23, head unit 260507, November 2026) from
`dumpsys com.hkmc.micom.cluster`, logcat and the cluster service's logs on drives with
the car's navigation and with Android Auto. Nothing here is the car's code; it describes
what the car does. [cluster-turns.md](cluster-turns.md) has the full turn format.

## What triggers the AR HUD

The AR HUD draws its animated arrow from the cluster's **main turn display**
(`NavigationCluster.updateTBTInfo`) alone:

- **The turn type has to be one of the car's own `Navigation.DisplayType` values**
  (`TURN` 13 with its angle, `U_TURN` 31, `FORK_2` 25, `EXIT_RIGHT`/`EXIT_LEFT` 17/18,
  roundabouts 32/33). Projection icons (64 and up) draw on the cluster and in the HUD's
  turn list, but the AR HUD doesn't animate them. The car's navigation sends its own
  types only near a maneuver (within `GUIDE_WINDOW` = 457 m / 1,500 ft) and the icons
  farther out, so the mod does the same.
- **The direction is the maneuver's angle** in 45 degree steps (0, 45/315, 90/270,
  135/225).
- **Side streets (`sideStreet`)** carry a 16-bit intersection road mask (one bit per 22.5
  degrees) on `TURN` (13) scenes — built from CarPlay's junction angles or Android Auto's
  intersection lane arrows, or 0 when no intersection geometry is available (never 65535
  on `TURN`, which draws a road in every direction).

This is the `tbt_standard` setting ("Enhanced cluster & AR HUD turn graphics", **on by
default**), implemented in `AaTurns.standard()` and `CpTurns.standard()`.

### CarPlay

The same setting puts CarPlay's turns in the car's own format (`CpTurns.standard()`):

- **The window:** within 457 m of a maneuver, from `RouteGuidanceUpdate` item 10
  (`DistanceToNextManeuver` in meters) or approximated from item 11/12 when item 10 is
  omitted.
- **The mapping:** from the iAP2 maneuver (angle-bucketed across all iAP2 `ManeuverType`s,
  driving side, junction type, and exit angle, with geometry preserved across partial
  distance updates):

| ManeuverType | Sent near the maneuver |
|---|---|
| 3, 6, 7 straight / plain roundabout entry | `TURN` 0 |
| 1, 20 / 2, 21 left / right (including end of road) | `TURN` 270 / 90, or the exit angle's 45 degree step |
| 49 / 50 slight left / right | `TURN` 315 / 45 |
| 47 / 48 sharp left / right | `TURN` 225 / 135 (315 / 45 when angle is slight) |
| 4, 26 U-turn | `U_TURN` 270 (90 for left-hand traffic) |
| 13, 52 / 14, 53 keep or change highway left / right | `FORK_2` 270 / 90 |
| 8, 22 / 23 highway off-ramp left / right | `EXIT_LEFT` 18 / `EXIT_RIGHT` 17 |
| 9 on-ramp with angle (`173`/`174`) | `TURN` 45 / 315 (unsided `172` and ferries `15`/`16` keep their icon with the bar) |
| roundabout exit icons (96-107 RHT, 192-203 LHT) | `EXIT_ROUNDABOUT_TRS_RIGHT` 32 (33 for left-hand traffic) with the icon's direction |
| arrival icons 112 / 113 / 114 | the icon, no bar; `ARRIVED` 3 within 20 m or in state 2 |

### Smooth 250 ms approaching-turn glide

The AR HUD arrow glides toward the intersection as the reported distance drops. Because
phones report turn distances roughly once per second, `AaTurns` and `CpTurns` interpolate
distance every 250 ms within `SMOOTH_MAX_M` (914 m) from observed vehicle velocity:

- If consecutive raw distance samples do not decrease (e.g. stopped at a red light),
  observed velocity drops to 0 immediately so the distance never rubber-bands or drifts.
- Under Android Auto, observed velocity continues smoothly even after `time_to_step`
  reaches `0 s` before the intersection.
- If the phone goes silent for more than 1.75 s, interpolation holds the last displayed
  distance rather than running past the turn.

## The distance bar and the speed limit (`NaviBridge`)

### Why the trap frame matters

The visual bar that counts down on the cluster isn't drawn from `updateTBTInfo` — the
cluster draws it from the **trap frame** (`NavigationCluster.updateTBTTrapInfo` /
`updateTBTTrapInfoNew`, field `barGraphLevel`), which shares a single MICOM packet with
the car's speed-limit sign:

| Byte | What |
|---|---|
| 8 | `barGraphLevel`: the distance bar (90 down to 10) |
| 9 | `speedLimit` |
| 10 | bus speed, speed-limit type and unit |
| 11 | speed info type |

### How `NaviBridge` splices the bar without losing speed limits

1. **In GPM (`AaTurns`) and DiPO (`CpTurns`)**, with `tbt_standard` on, the active
   approach bar (`90..10` inside `GUIDE_WINDOW`, `0` outside, `-1` on route end) is sent
   via `hmgapi.mods.wireless.PHONE_TBT` targeted to `com.mnsoft.navi`.
2. **In `com.mnsoft.navi` (`NaviBridge`)**, hooks on
   `framework.navigation_cluster.update_tbt_trap_info` (17 `int`s, bar at index 11) and
   `framework.navigation_cluster.update_tbt_trap_info_new` (`NaviTbtTrapInfo` cloned via
   `framework.navi_tbt_trap_info.new` + `bar_graph_level`) capture stock navigation's
   clean baseline trap frames and splice the active phone `bar` into outgoing copies.
   Every other field — `speedLimit`, unit, type, cameras, and warnings — stays stock
   navigation's own.
3. **When the phone's bar changes**, `NaviBridge` re-emits a copy of the latest captured
   stock trap frame with the updated `bar` (rate-limited to 200 ms), because stock
   navigation only emits trap frames when its own map speed limit changes.
4. **Fail-safe recovery.** When phone guidance ends (`active = false`) or the 6-second
   lease expires, `NaviBridge` restores `barGraphLevel = 0` on the latest stock trap
   frame and replays stock navigation's captured `DisplayType = 8` compass frame.

## Checking on a car

- `adb shell dumpsys com.hkmc.micom.cluster`: prints the last turn frame (type by name,
  side streets, direction, distance) and trap frame (`barGraphLevel`, `speedLimit`).
- logcat, tag `ClusterService`: each `NaviCluster.updateTBTTrapInfo()` with its
  `barGraphLevel` and `speedLimit`.
- HMG-API's log (`/data/hmgapi/logs/hmgapi/log.log`): logs
  `navigation: approach countdown bar spliced into stock trap frames` when the trap hooks
  install in `com.mnsoft.navi`.
