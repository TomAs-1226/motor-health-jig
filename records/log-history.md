# Motor history from robot logs

Usage before each motor came to the jig. It comes from the CTRE `.hoot` logs left on X1's roboRIO: all 243 rio-bus logs that contain these motors' CAN ids, extracted on 2026-11-18 with owlet 26.3.0. The logs record each motor by **CAN id, not serial**. The serials below are today's, matched by CAN id and Tuner name, so the history is very likely the same motor but not proven.

| # | Serial | CAN id | Made | Role on the 2026 robot | Sessions | Ran while enabled | Turning | Under load ≥5 A | Over 40 A | Peak stator | Motor faults | Estimate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `000E173405DC0000001900010B00011A` | 11 | Jul 2024 | Front-left steer | 169 | 58 | short moves¹ | 5.3 min | 20 s | 69 A | none² | GOOD |
| 2 | `000E1670085CA00000160000D60000F9` | 30 | Jan 2024 | Left flywheel, then X1 front-left drive | 176 | 89 | 63 min, 44k rev | **56 min** | **128 s** | 68 A | none³ | GOOD, most worked: watch |
| 3 | `000E173405DC0000001900010600011B` | 12 | Jul 2024 | Front-left drive | 181 | 71 | 31 min, 25k rev | 5.7 min | 62 s | 140 A | none | GOOD |
| 4 | `000E173405DC00000019000125000109` | 17 | Jul 2024 | Back-right steer | 169 | 64 | short moves¹ | 6.7 min | 22 s | 63 A | none² | GOOD |
| 5 | `000E173405DC0000001900012A000108` | 15 | Jul 2024 | Drive ("Black Left Drive") | 169 | 76 | 8.6 min, 11k rev | 5.5 min | 79 s | 133 A | none³ | GOOD |
| 6 | `000E0AE7074F1800001900011600011C` | 20 | Jun 2024 | Front-right steer | 220⁴ | 74 | short moves¹ | 7.2 min | 27 s | 67 A | none² | GOOD |
| 7 | `000E173405DC00000019000127000105` | 18 | Jul 2024 | Back-right drive | 169 | 75 | 8.5 min, 11k rev | 5.3 min | 62 s | 129 A | none | GOOD |
| 8 | `000E1734060EC80000180000DA00010C` | 50 | Jan 2024 | Climber ("climberGB") | 0 | — | — | — | — | — | — | unknown⁵ |

1. Steer speed is logged in module turns, not motor turns, so the motor turned roughly 10–20× more than these figures show.
2. `RemoteSensorDataInvalid` was raised 110 times on #1, 160 on #4 and 144 on #6. It means the steer motor lost its CANcoder signal: a robot wiring problem, not a motor fault.
3. `BridgeBrownout` was raised once each on #2 and #5, when the robot battery sagged to 7.5 V.
4. CAN id 20 was also used on a second, smaller robot logged on this rio (with ids 2, 5 and 6). Some of #6's sessions may belong to a different motor.
5. CAN id 50 is in none of the logs on X1's rio; its climber history is on another robot. It is still on 2025 firmware (25.5.1.0): flash 2026 firmware in Tuner X before testing it on the jig.

None of the seven motors with logs ever raised `Fault_DeviceTemp`, `Fault_Hardware`, `Fault_RotorFault1/2` or `Fault_BootDuringEnable`. The logs don't record temperature itself.

**Estimate before the spin test:** all seven with history are likely GOOD. #2 has about ten times the load time of any other motor and is the one to watch. #3, #5 and #7 are the drives with the hardest current spikes. #8 is unknown. The jig's free-spin test is what checks bearings, Kv and free current.
