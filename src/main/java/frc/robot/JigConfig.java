package frc.robot;

import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.PowerDistribution.ModuleType;
import edu.wpi.first.wpilibj.RobotBase;
import java.nio.file.Path;

/** Everything about the jig that is wired, not measured. Change these to match the bench. */
public final class JigConfig {
  private JigConfig() {}

  public static final int TEAM = 5805;

  /** REV PDH ships as CAN id 1. */
  public static final int PDH_CAN_ID = 1;

  public static final ModuleType PDH_TYPE = ModuleType.kRev;

  /**
   * The Phoenix diagnostic server inside this robot program (the one Tuner X talks to). It is the
   * only place the motor's serial number comes from.
   */
  public static final String DIAGNOSTIC_SERVER = "http://127.0.0.1:1250/";

  /**
   * CAN ids tried one by one when the diagnostic server gives no device list. Motors found this way
   * are still tested, but their record has no serial number.
   */
  public static final int[] FALLBACK_IDS = range(0, 20);

  /**
   * Where records are written: on the roboRIO beside the program, in the simulator under the
   * project.
   */
  public static Path recordDir() {
    return RobotBase.isReal() ? Path.of("/home/lvuser/motor-health") : Path.of("motor-health-sim");
  }

  /** The PDH, or null if it could not be opened. The jig still runs without it. */
  public static PowerDistribution openPdh() {
    try {
      return new PowerDistribution(PDH_CAN_ID, PDH_TYPE);
    } catch (RuntimeException e) {
      System.err.println("[MotorHealth] No PDH at CAN id " + PDH_CAN_ID + ": " + e.getMessage());
      return null;
    }
  }

  private static int[] range(int from, int toInclusive) {
    int[] ids = new int[toInclusive - from + 1];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = from + i;
    }
    return ids;
  }
}
