package frc.robot;

import java.util.Map;

/**
 * What a motor of this type should do with no load, at 12 V, trapezoidal commutation (the jig never
 * uses FOC, so it needs no Pro licence and every motor is compared the same way).
 *
 * <p>An unknown value is {@code NaN}, never a guess: the estimator skips a comparison it has no
 * honest number for and falls back on the fleet of motors already tested.
 *
 * @param motorType Phoenix's {@code ConnectedMotorValue} name, e.g. {@code KrakenX60_Integrated}
 * @param freeSpeedRps rotor free speed at 12 V
 * @param freeCurrentA current at free speed
 * @param source where the free speed came from
 */
public record MotorSpec(String motorType, double freeSpeedRps, double freeCurrentA, String source) {

  /** Vendor datasheet values. Free speed in RPM, free current in A. */
  private static final Map<String, double[]> DATASHEET =
      Map.of(
          "Falcon500_Integrated", new double[] {6380, 1.5},
          "KrakenX60_Integrated", new double[] {6000, 2.0},
          "KrakenX44_Integrated", new double[] {7530, Double.NaN});

  /**
   * The spec for a motor.
   *
   * @param motorType Phoenix's connected-motor name
   * @param kvReported the Kv the Talon itself reports ({@code getMotorKV}), in whatever unit it
   *     arrives; used only for a motor type the datasheet table does not have
   */
  public static MotorSpec lookup(String motorType, double kvReported) {
    double[] sheet = DATASHEET.get(motorType);
    if (sheet != null) {
      return new MotorSpec(motorType, sheet[0] / 60.0, sheet[1], "datasheet");
    }
    double kv = kvRpsPerVolt(kvReported);
    if (Double.isFinite(kv)) {
      return new MotorSpec(motorType, kv * 12.0, Double.NaN, "Talon-reported Kv");
    }
    return new MotorSpec(motorType, Double.NaN, Double.NaN, "unknown");
  }

  /**
   * Kv in rotations per second per volt, from a value that may be RPM/V or rps/V. Phoenix documents
   * RPM/V; the range check keeps a unit change from producing a 60x wrong spec.
   */
  static double kvRpsPerVolt(double raw) {
    if (!Double.isFinite(raw)) {
      return Double.NaN;
    }
    if (raw >= 150 && raw <= 2000) {
      return raw / 60.0;
    }
    if (raw >= 2.5 && raw <= 35) {
      return raw;
    }
    return Double.NaN;
  }
}
