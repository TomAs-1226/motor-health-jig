package frc.robot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One steady-state voltage step, averaged.
 *
 * @param volts commanded output
 * @param samples loops averaged
 * @param durationS time between the first and last sample
 * @param meanVelRps rotor velocity
 * @param stdVelRps rotor velocity spread (roughness)
 * @param meanStatorA stator current: the torque the rotor needs to overcome its own friction
 * @param meanSupplyA supply current as the Talon measures it
 * @param meanBusV supply voltage at the Talon
 * @param positionDeltaRot rotor rotations counted by the encoder over the window
 * @param pdhMeanA mean current on every PDH channel, empty without a PDH
 */
public record StepResult(
    double volts,
    int samples,
    double durationS,
    double meanVelRps,
    double stdVelRps,
    double meanStatorA,
    double meanSupplyA,
    double meanBusV,
    double positionDeltaRot,
    double[] pdhMeanA) {

  /**
   * Encoder-counted rotations over velocity-predicted rotations: 1.0 when the two agree. NaN when
   * the motor barely moved.
   */
  public double encoderAgreement() {
    double expected = meanVelRps * durationS;
    if (Math.abs(expected) < 0.5) {
      return Double.NaN;
    }
    return positionDeltaRot / expected;
  }

  /** The PDH channel carrying the most current, or -1 with no PDH. */
  public int busiestPdhChannel() {
    int best = -1;
    for (int i = 0; i < pdhMeanA.length; i++) {
      if (best < 0 || pdhMeanA[i] > pdhMeanA[best]) {
        best = i;
      }
    }
    return best;
  }

  Map<String, Object> toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("volts", volts);
    m.put("samples", samples);
    m.put("durationS", Json.round(durationS, 3));
    m.put("meanVelRps", Json.round(meanVelRps, 3));
    m.put("stdVelRps", Json.round(stdVelRps, 4));
    m.put("meanStatorA", Json.round(meanStatorA, 3));
    m.put("meanSupplyA", Json.round(meanSupplyA, 3));
    m.put("meanBusV", Json.round(meanBusV, 3));
    m.put("positionDeltaRot", Json.round(positionDeltaRot, 3));
    List<Double> pdh = new ArrayList<>();
    for (double a : pdhMeanA) {
      pdh.add(Json.round(a, 3));
    }
    m.put("pdhMeanA", pdh);
    return m;
  }

  /** Builds a {@link StepResult} one loop at a time. */
  public static final class Accumulator {
    private final double volts;
    private int n;
    private double firstT = Double.NaN;
    private double lastT;
    private double velMean;
    private double velM2;
    private double statorSum;
    private double supplySum;
    private double busSum;
    private double firstPos;
    private double lastPos;
    private double[] pdhSum = new double[0];

    public Accumulator(double volts) {
      this.volts = volts;
    }

    public void add(
        double t, double vel, double pos, double stator, double supply, double busV, double[] pdh) {
      if (n == 0) {
        firstT = t;
        firstPos = pos;
        pdhSum = new double[pdh.length];
      }
      n++;
      lastT = t;
      lastPos = pos;
      double d = vel - velMean;
      velMean += d / n;
      velM2 += d * (vel - velMean);
      statorSum += stator;
      supplySum += supply;
      busSum += busV;
      for (int i = 0; i < Math.min(pdh.length, pdhSum.length); i++) {
        pdhSum[i] += pdh[i];
      }
    }

    public StepResult result() {
      double[] pdh = new double[pdhSum.length];
      for (int i = 0; i < pdh.length; i++) {
        pdh[i] = n == 0 ? 0 : pdhSum[i] / n;
      }
      return new StepResult(
          volts,
          n,
          n == 0 ? 0 : lastT - firstT,
          velMean,
          n > 1 ? Math.sqrt(velM2 / (n - 1)) : 0,
          n == 0 ? 0 : statorSum / n,
          n == 0 ? 0 : supplySum / n,
          n == 0 ? 0 : busSum / n,
          lastPos - firstPos,
          pdh);
    }
  }
}
