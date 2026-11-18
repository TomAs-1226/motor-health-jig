# Motor history from robot logs

Usage before each motor came to the jig. It comes from the CTRE `.hoot` logs left on X1's roboRIO and was extracted on 2026-11-18 with owlet 26.3.0. 158 of the 192 relevant logs were read; the other 34 were still on the rio when the connection dropped. The logs record each motor by **CAN id, not serial**. The serials below are today's, matched by CAN id and Tuner name, so the history is very likely the same motor but not proven.

| # | Serial | CAN id | Role on the 2026 robot | Sessions present | Ran while enabled | Turning | Under load ≥5 A | Over 40 A | Peak stator | Motor faults |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `000E173405DC0000001900010B00011A` | 11 | Front-left steer | 138 | 48 | short moves¹ | 1.9 min | 6 s | 62 A | none² |
| 2 | `000E1670085CA00000160000D60000F9` | 30 | Left flywheel, then X1 front-left drive | 144 | 77 | 42 min, 29k rev | 38 min | 73 s | 62 A | none³ |
| 3 | `000E173405DC0000001900010600011B` | 12 | Front-left drive | 150 | 62 | 28 min, 23k rev | 2.8 min | 28 s | 140 A | none |
| 4 | `000E173405DC00000019000125000109` | 17 | Back-right steer | 138 | 54 | short moves¹ | 2.2 min | 8 s | 61 A | none² |
| 5 | `000E173405DC0000001900012A000108` | 15 | Drive ("Black Left Drive") | 138 | 65 | 5.6 min, 8.9k rev | 2.6 min | 37 s | 133 A | none³ |
| 6 | `000E0AE7074F1800001900011600011C` | 20 | Front-right steer | 138 | 62 | short moves¹ | 4.2 min | 14 s | 67 A | none² |

1. Steer speed is logged in module turns, not motor turns, so the motor turned roughly 10–20× more than these figures.
2. `RemoteSensorDataInvalid` was raised 64 times on #1, 99 on #4 and 99 on #6. It means the steer motor lost its CANcoder signal: a robot wiring problem, not a motor fault.
3. `BridgeBrownout` was raised once each on #2 and #5, when the robot battery sagged to 7.5 V.

None of the six ever raised `Fault_DeviceTemp`, `Fault_Hardware`, `Fault_RotorFault1/2` or `Fault_BootDuringEnable`. The logs don't record temperature itself.

**Estimate before the spin test: all six likely GOOD. #2 is the most worked (sustained flywheel load) and the one to watch; #3 and #5 are the drives with the hardest current spikes.** The jig's free-spin test is what checks bearings, Kv and free current.
