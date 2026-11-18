# Motor health jig

A bench test for Talon FX motors (Falcon 500, Kraken X60, Kraken X44). It runs on a roboRIO wired to a REV PDH and records each motor by its serial number. Plug a motor into the jig, enable Test mode, and about 15 seconds later you have a verdict and a JSON record. The record follows the motor even after its CAN id or name changes.

Hardware: Catalyst X1's old roboRIO, a REV PDH (CAN id 1), a battery, and the motor under test with **nothing on its shaft**.

## Why not Catalyst 2.0 directly

The idea comes from Catalyst 2.0's `MotorHistory` (`FrcCatalyst-alpha7/src/main/java/frc/lib/catalyst/identity/MotorHistory.java`): use the serial number as the motor's identity, read it from the Phoenix diagnostic server, and keep the record as one file. Catalyst 2.x runs only on Systemcore (WPILib 2027, Phoenix 26.70 alpha, Java 25, Commands v3). A roboRIO needs the 2026 line, so this project is standalone:

| | Catalyst 2.0 `MotorHistory` | This jig |
|---|---|---|
| Controller | Systemcore | roboRIO |
| Stack | WPILib 2027 alpha-7, Phoenix 26.70.0-alpha-2, Java 25 | WPILib 2026.2.1, Phoenix 26.3.0, Java 17 |
| What it does | Passively logs usage on a robot (hours, revolutions, peak temperature) | Actively spins each motor and scores its health |
| Identity | Diagnostic server `?action=getdevices` on port 1250 | Same endpoint, same fields (ported) |
| Record | One `motor-history.json` on the robot | One JSON per test, pulled into `records/` and committed to git |

Motors on a 2026 robot run 2026 firmware. A motor that was flashed to 26.70 for Systemcore has to be flashed back in Tuner X before this jig can see it.

## Using it

1. Deploy once: `./gradlew deploy`. Records live in `/home/lvuser/motor-health/`, which deploys never touch.
2. Plug in the motor: CAN to the rio, power to a PDH channel. Its CAN id doesn't matter, and a new motor at id 0 is fine. Make sure the shaft spins freely.
3. Optional: in Elastic or Shuffleboard, type the sticker label into `MotorHealth/Label` (and anything else into `MotorHealth/Notes`).
4. In the Driver Station, choose **Test** and enable. Every Talon FX on the bus is tested one after another. Watch `MotorHealth/Status`.
5. Results appear under `MotorHealth/Last/*` (Verdict, Score, Summary, Flags) and in the console log.
6. Disable, swap the motor, enable again. Disabling at any point stops the motor at once, and an interrupted run is not recorded.

### Getting the records into git

```bash
python tools/pull_records.py
```

The script copies `/home/lvuser/motor-health/tests/*.json` from the rio into `records/tests/` over scp. The `lvuser` password is empty, so press Enter if asked. It never deletes anything on the rio and never overwrites a file that is already in `records/tests/`. Then it rebuilds:

- `records/README.md`: a table of every motor and its newest result, readable on GitHub
- `records/motors.csv`: the same table, one row per motor
- `records/history.csv`: one row per test

Commit `records/` and push it when you're done.

### Connecting to the rio

X1's rio is a roboRIO 2 on image `FRC_roboRIO2_2026_v1.2`, team 5805, Java 17. Its CAN devices are on 2026 firmware (26.1.1.1).

- **USB-B** is the simplest. The rio is always `172.22.11.2` over USB.
- **Ethernet straight to the laptop** works too. With no DHCP on the cable, both ends give themselves a `169.254.x.x` address, and `roboRIO-5805-FRC.local` finds the rio by mDNS.
- **Tailscale blocks the Ethernet route.** Tailscale claims the whole `169.254.0.0/16` route, so traffic meant for the rio goes into the tunnel. Deploy, the Driver Station and `pull_records.py` then fail even though mDNS resolves the name. **Disconnect Tailscale while you're on the jig.** Alternatively, run `pull_records.py --host <rio 169.254 address> --bind <laptop's Ethernet 169.254 address>`.
- **The rio's clock starts wrong.** It has no clock battery: X1's booted thinking it was December 2024. The Driver Station sets the real time when it connects. A record made before then is saved with `clockTrusted: false`.

## The test

| Phase | What happens | What it shows |
|---|---|---|
| Prepare (1 s) | Neutral. Reads sticky faults, then clears them | Faults the motor arrived with |
| +3, +6, +9 V | Each step settles 1.5 s, then averages 1.0 s | Speed per volt (Kv) and free current |
| Coast from +9 V | Lets go and times the drop to half speed | Mechanical drag, with no electronics involved |
| −6 V | Settles and averages | Forward vs reverse asymmetry |
| Coast to stop | | |

All runs use open-loop `VoltageOut` with no FOC, so no Pro licence is needed and every motor is driven the same way. The run uses rotor signals, so a gear ratio left in a motor's config doesn't matter. The run stops early, and records why, if the motor doesn't spin, pulls over 35 A unloaded, or drops off CAN.

**The jig rewrites four config groups on each motor**: MotorOutput (coast, CCW+), CurrentLimits (40 A stator, 30 A supply), OpenLoopRamps (0.25 s), and Voltage (defaults). It doesn't touch the CAN id, the name or anything else. Robot code re-applies its own config at boot.

### Scoring

The run starts at 100. Each WARN costs 12 points and each FAIL costs 45. Any FAIL makes the verdict **FAIL**, any WARN makes it **WATCH**, and otherwise the motor is **GOOD**. Every threshold is in `HealthEstimator.Limits`.

| Check | Compared against | WARN / FAIL |
|---|---|---|
| Projected 12 V free speed | Datasheet (Falcon 6380, X60 6000, X44 7530 rpm) | ±8% / ±15% |
| Free current | Datasheet (Falcon 1.5 A, X60 2 A; X44 unknown, so skipped) | 1.6× / 2.5× |
| Kv, free current, coast-down | Median of ≥3 motors of the same type already tested on this jig | 5%/10%, 1.5×/2.2×, 60%/35% |
| Forward vs reverse speed | Itself | 5% / 12% |
| Speed wobble at steady state | Itself | 4% (WARN only) |
| Encoder rotations vs velocity | Itself | 5% / 15% |
| Temperature rise over the run | | 8 °C (WARN only) |
| Faults during the run | | Hardware, rotor, reboot or stator limit: FAIL. Others: WARN |
| Sticky faults on arrival | | Hardware or rotor: WARN. Others: INFO |
| PDH channel current vs Talon supply current | | Gross mismatch: WARN (check the leads) |
| Jig battery below 10.5 V | | WARN: recharge and retest |

**The thresholds are first estimates and have not been checked on hardware.** Before trusting a FAIL, run three or four motors you know are good (that also builds the fleet baseline) and one you know is bad, then adjust `Limits`. A free-spin test cannot see torque under load, or a winding that fails only when hot.

## Motor numbers

Every motor gets a team number (#1, #2, #3…), which is only there to keep motors in order. The motor's identity is its serial number, and every record also keeps the CAN id, Tuner name, model, firmware, hardware and bootloader revisions, manufacture date and Pro licence.

- A motor the jig has never seen gets the next number after the highest in use. Write it on the motor: the jig shows it in `MotorHealth/Last/Number`.
- Motors numbered by hand are listed in `src/main/deploy/motor-numbers.json`. A number there always wins, and those motors appear in `records/README.md` even before their first test.
- #1 and #2 are the two motors from X1's rio. Their histories are in the seed file's notes.

## What a record holds

`tests/20261117-200102_SN-<serial>.json` contains:

- **motor**: serial number (the factory id that stays with the motor), CAN id, model, name, firmware, hardware revision, manufacture date, motor type, and the spec it was judged against
- **result**: verdict, score, summary, every flag with a plain-English reason, and the metrics
- **steps**: the raw averages for each voltage step, including current on every PDH channel
- coast-down times, faults, the fleet baseline it was compared with, other devices on the jig (for example a CANcoder and its serial), the jig's rio serial, the label and notes, and whether the rio's clock was set (the Driver Station sets it when it connects)

If the diagnostic server doesn't answer, the jig falls back to probing CAN ids 0 to 20. It still tests the motors it finds, but their records have no serial (`serialSource` says so).

## Development

```bash
./gradlew build
```

`build` compiles and runs the unit tests. The health maths, discovery parsing, fleet baseline and record files are all tested without hardware. On this laptop, set `JAVA_HOME` to `C:\Users\Public\wpilib\2026\jdk` first, because the ambient JDK 21 fits neither line.

| File | What |
|---|---|
| `Robot.java` | Test mode starts a session, and disable stops it |
| `JigSession.java` | Discovers motors, queues them, builds and saves each record |
| `MotorTestRun.java` | One motor's run as a state machine, one step per loop |
| `HealthEstimator.java` | Pure scoring logic. All thresholds are here |
| `FleetBaseline.java` | Median of the motors already tested |
| `DeviceDiscovery.java` | Phoenix diagnostic server client (serial numbers) |
| `MotorSpec.java` | Datasheet values |
| `RecordStore.java` | JSON and CSV on the rio |
| `JigConfig.java` | PDH id, fallback ids, record folder |
| `tools/pull_records.py` | rio → `records/`, plus the summaries |
