package frc.robot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns one bench run into a verdict. Pure: no hardware, no clock, so every rule is unit-tested.
 *
 * <h2>What a free-spinning motor can tell you</h2>
 *
 * <ul>
 *   <li><b>Speed per volt</b> (Kv). Low: friction or a dragging rotor. High: weakened magnets.
 *   <li><b>Free current</b>. The torque it takes just to turn itself. Rises with worn bearings,
 *       rubbing, debris, a bent shaft.
 *   <li><b>Coast-down</b>. How long it keeps spinning on its own. Falls with the same friction,
 *       measured without any electronics in the loop.
 *   <li><b>Direction asymmetry</b>, <b>velocity roughness</b>, <b>encoder agreement</b>.
 *   <li><b>Faults</b> during the run, and sticky faults it arrived with.
 * </ul>
 *
 * <p>It cannot tell you about torque under load or a winding that only fails hot; the jig spins
 * motors unloaded. The thresholds in {@link Limits} are first estimates: calibrate them against
 * motors you know are good and bad.
 */
public final class HealthEstimator {
  private HealthEstimator() {}

  public static final String GOOD = "GOOD";
  public static final String WATCH = "WATCH";
  public static final String FAIL = "FAIL";

  /** Every threshold in one place. Fractions are of the reference value. */
  public static final class Limits {
    private Limits() {}

    /** Projected 12 V free speed vs datasheet. Jig losses make a few percent under normal. */
    public static final double SPEED_VS_SPEC_WARN = 0.08;

    public static final double SPEED_VS_SPEC_FAIL = 0.15;

    /** Free current as a multiple of the datasheet value. */
    public static final double CURRENT_VS_SPEC_WARN = 1.6;

    public static final double CURRENT_VS_SPEC_FAIL = 2.5;

    /** Kv vs the fleet median: same jig, so tighter. */
    public static final double KV_VS_FLEET_WARN = 0.05;

    public static final double KV_VS_FLEET_FAIL = 0.10;

    /** Free current as a multiple of the fleet median. */
    public static final double CURRENT_VS_FLEET_WARN = 1.5;

    public static final double CURRENT_VS_FLEET_FAIL = 2.2;

    /** Coast-down half-speed time as a fraction of the fleet median. Lower is worse. */
    public static final double COAST_VS_FLEET_WARN = 0.6;

    public static final double COAST_VS_FLEET_FAIL = 0.35;

    /** Forward vs reverse speed at the same voltage. */
    public static final double ASYMMETRY_WARN = 0.05;

    public static final double ASYMMETRY_FAIL = 0.12;

    /** Velocity standard deviation over mean, at 6 V and above. */
    public static final double ROUGHNESS_WARN = 0.04;

    /** Encoder-counted vs velocity-predicted rotations. */
    public static final double ENCODER_WARN = 0.05;

    public static final double ENCODER_FAIL = 0.15;

    /** Temperature rise over one run. A healthy unloaded motor barely warms. */
    public static final double TEMP_RISE_WARN_C = 8.0;

    /** Bus voltage below this during the top step makes the speed numbers unreliable. */
    public static final double LOW_BUS_V = 10.5;

    /** PDH channel vs Talon supply current disagreement worth a look. */
    public static final double PDH_MISMATCH_MIN_A = 2.0;

    public static final double PDH_MISMATCH_FRACTION = 0.5;

    /** A PDH channel carrying less than this is not the motor's. */
    public static final double PDH_CHANNEL_MIN_A = 0.25;

    public static final int SCORE_PER_WARN = 12;
    public static final int SCORE_PER_FAIL = 45;
  }

  /** Faults that end a motor's career if they appear during a free-spin run. */
  static final Set<String> FAIL_FAULTS =
      Set.of("Hardware", "RotorFault1", "RotorFault2", "BootDuringEnable", "StatorCurrLimit");

  /** Sticky faults worth a warning on arrival. The rest are history, reported as info. */
  static final Set<String> WARN_STICKY = Set.of("Hardware", "RotorFault1", "RotorFault2");

  public enum Level {
    INFO,
    WARN,
    FAIL
  }

  public record Flag(Level level, String code, String message) {
    Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("level", level.name());
      m.put("code", code);
      m.put("message", message);
      return m;
    }
  }

  /**
   * One run's measurements.
   *
   * @param coastHalfS seconds to coast from the top step to half speed, NaN if not measured
   * @param abortReason why the run stopped early because of the motor, or null
   * @param fleet the baseline for this motor type, or null when there are too few motors
   */
  public record Inputs(
      MotorSpec spec,
      List<StepResult> steps,
      double coastHalfS,
      double tempStartC,
      double tempEndC,
      Set<String> faultsDuringTest,
      Set<String> stickyBefore,
      String abortReason,
      FleetBaseline.Stats fleet) {}

  public record Report(String verdict, int score, List<Flag> flags, Map<String, Double> metrics) {
    /** The non-info flags in one line, or a clean bill. */
    public String summary() {
      String s =
          flags.stream()
              .filter(f -> f.level() != Level.INFO)
              .map(f -> f.level() + " " + f.code())
              .collect(Collectors.joining(", "));
      return s.isEmpty() ? "all checks passed" : s;
    }

    Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("verdict", verdict);
      m.put("score", score);
      m.put("summary", summary());
      List<Object> fl = new ArrayList<>();
      for (Flag f : flags) {
        fl.add(f.toJson());
      }
      m.put("flags", fl);
      Map<String, Object> mm = new LinkedHashMap<>();
      metrics.forEach((k, v) -> mm.put(k, Json.round(v, 4)));
      m.put("metrics", mm);
      return m;
    }
  }

  public static Report estimate(Inputs in) {
    List<Flag> flags = new ArrayList<>();
    Map<String, Double> metrics = new LinkedHashMap<>();

    if (in.abortReason() != null) {
      flags.add(new Flag(Level.FAIL, "Aborted", in.abortReason()));
    }

    List<StepResult> fwd =
        in.steps().stream()
            .filter(s -> s.volts() > 0)
            .sorted(Comparator.comparingDouble(StepResult::volts))
            .collect(Collectors.toList());
    StepResult top = fwd.isEmpty() ? null : fwd.get(fwd.size() - 1);

    speed(in, fwd, flags, metrics);
    if (top != null) {
      freeCurrent(in, top, flags, metrics);
      pdh(top, flags, metrics);
      if (top.meanBusV() > 0 && top.meanBusV() < Limits.LOW_BUS_V) {
        flags.add(
            new Flag(
                Level.WARN,
                "LowJigBattery",
                String.format(
                    "Bus at %.1f V during the %.0f V step: charge the jig battery and retest",
                    top.meanBusV(), top.volts())));
      }
    }
    asymmetry(in.steps(), flags, metrics);
    roughnessAndEncoder(fwd, in.steps(), flags, metrics);
    coast(in, flags, metrics);
    temperature(in, flags, metrics);
    faults(in, flags);

    boolean noSpec = in.spec() == null || !Double.isFinite(in.spec().freeSpeedRps());
    if (noSpec && in.fleet() == null) {
      flags.add(
          new Flag(
              Level.INFO,
              "NoReference",
              "No datasheet speed and too few tested motors of this type: speed was not judged"));
    }

    long warns = flags.stream().filter(f -> f.level() == Level.WARN).count();
    long fails = flags.stream().filter(f -> f.level() == Level.FAIL).count();
    int score =
        (int)
            Math.max(0, 100 - warns * Limits.SCORE_PER_WARN - fails * Limits.SCORE_PER_FAIL);
    String verdict = fails > 0 ? FAIL : warns > 0 ? WATCH : GOOD;
    return new Report(verdict, score, flags, metrics);
  }

  // ---------------------------------------------------------------- the checks

  private static void speed(
      Inputs in, List<StepResult> fwd, List<Flag> flags, Map<String, Double> metrics) {
    if (fwd.size() < 2) {
      return;
    }
    double[] fit = fit(fwd);
    double kv = fit[0];
    double projected = kv * 12.0 + fit[1];
    metrics.put("kvRpsPerV", kv);
    metrics.put("projectedFreeRpm12V", projected * 60.0);

    if (in.spec() != null && Double.isFinite(in.spec().freeSpeedRps())) {
      double dev = projected / in.spec().freeSpeedRps() - 1.0;
      metrics.put("speedVsSpec", dev);
      Level level = band(Math.abs(dev), Limits.SPEED_VS_SPEC_WARN, Limits.SPEED_VS_SPEC_FAIL);
      if (level != null) {
        flags.add(
            new Flag(
                level,
                dev < 0 ? "SlowVsSpec" : "FastVsSpec",
                String.format(
                    "Projected 12 V free speed %.0f rpm vs %.0f rpm spec (%+.1f%%): %s",
                    projected * 60,
                    in.spec().freeSpeedRps() * 60,
                    dev * 100,
                    dev < 0 ? "friction or a dragging rotor" : "possibly weakened magnets")));
      }
    }
    if (in.fleet() != null && Double.isFinite(in.fleet().kvRpsPerV())) {
      double dev = kv / in.fleet().kvRpsPerV() - 1.0;
      metrics.put("kvVsFleet", dev);
      Level level = band(Math.abs(dev), Limits.KV_VS_FLEET_WARN, Limits.KV_VS_FLEET_FAIL);
      if (level != null) {
        flags.add(
            new Flag(
                level,
                dev < 0 ? "SlowVsFleet" : "FastVsFleet",
                String.format(
                    "Kv %.2f rps/V is %+.1f%% from the median of %d tested %s",
                    kv, dev * 100, in.fleet().motors(), in.fleet().motorType())));
      }
    }
  }

  private static void freeCurrent(
      Inputs in, StepResult top, List<Flag> flags, Map<String, Double> metrics) {
    double amps = top.meanStatorA();
    metrics.put("freeCurrentA", amps);
    if (in.spec() != null && Double.isFinite(in.spec().freeCurrentA())) {
      double ratio = amps / in.spec().freeCurrentA();
      metrics.put("freeCurrentVsSpec", ratio);
      Level level = band(ratio, Limits.CURRENT_VS_SPEC_WARN, Limits.CURRENT_VS_SPEC_FAIL);
      if (level != null) {
        flags.add(
            new Flag(
                level,
                "HighFreeCurrent",
                String.format(
                    "%.2f A spinning free at %.0f V, %.1fx the %.1f A spec: bearings, rubbing or"
                        + " debris",
                    amps, top.volts(), ratio, in.spec().freeCurrentA())));
      }
    }
    if (in.fleet() != null && Double.isFinite(in.fleet().freeCurrentA())) {
      double ratio = amps / in.fleet().freeCurrentA();
      metrics.put("freeCurrentVsFleet", ratio);
      Level level = band(ratio, Limits.CURRENT_VS_FLEET_WARN, Limits.CURRENT_VS_FLEET_FAIL);
      if (level != null) {
        flags.add(
            new Flag(
                level,
                "HighFreeCurrentVsFleet",
                String.format(
                    "%.2f A free current is %.1fx the median of %d tested %s",
                    amps, ratio, in.fleet().motors(), in.fleet().motorType())));
      }
    }
  }

  private static void pdh(StepResult top, List<Flag> flags, Map<String, Double> metrics) {
    int ch = top.busiestPdhChannel();
    if (ch < 0) {
      return;
    }
    double pdhA = top.pdhMeanA()[ch];
    if (pdhA < Limits.PDH_CHANNEL_MIN_A) {
      flags.add(
          new Flag(
              Level.INFO,
              "PdhChannelUnknown",
              "No PDH channel carried the motor's current; is the jig feeding it through the PDH?"));
      return;
    }
    metrics.put("pdhChannel", (double) ch);
    metrics.put("pdhCurrentA", pdhA);
    double diff = Math.abs(pdhA - top.meanSupplyA());
    if (diff > Math.max(Limits.PDH_MISMATCH_MIN_A, Limits.PDH_MISMATCH_FRACTION * pdhA)) {
      flags.add(
          new Flag(
              Level.WARN,
              "PdhMismatch",
              String.format(
                  "PDH channel %d reads %.2f A but the Talon reports %.2f A supply: check the"
                      + " power leads and connector",
                  ch, pdhA, top.meanSupplyA())));
    }
  }

  private static void asymmetry(
      List<StepResult> steps, List<Flag> flags, Map<String, Double> metrics) {
    for (StepResult rev : steps) {
      if (rev.volts() >= 0) {
        continue;
      }
      for (StepResult f : steps) {
        if (f.volts() == -rev.volts()) {
          double a = Math.abs(f.meanVelRps());
          double b = Math.abs(rev.meanVelRps());
          if (a + b <= 0) {
            continue;
          }
          double asym = (a - b) / ((a + b) / 2.0);
          metrics.put("asymmetry", asym);
          Level level = band(Math.abs(asym), Limits.ASYMMETRY_WARN, Limits.ASYMMETRY_FAIL);
          if (level != null) {
            flags.add(
                new Flag(
                    level,
                    "Asymmetric",
                    String.format(
                        "%.1f rps forward vs %.1f rps reverse at %.0f V (%+.1f%%): commutation or"
                            + " a rotor that drags one way",
                        a, b, f.volts(), asym * 100)));
          }
          return;
        }
      }
    }
  }

  private static void roughnessAndEncoder(
      List<StepResult> fwd, List<StepResult> all, List<Flag> flags, Map<String, Double> metrics) {
    double rough = 0;
    for (StepResult s : fwd) {
      if (s.volts() >= 6 && Math.abs(s.meanVelRps()) > 1) {
        rough = Math.max(rough, s.stdVelRps() / Math.abs(s.meanVelRps()));
      }
    }
    if (rough > 0) {
      metrics.put("roughness", rough);
      if (rough > Limits.ROUGHNESS_WARN) {
        flags.add(
            new Flag(
                Level.WARN,
                "Rough",
                String.format(
                    "Speed wobbles %.1f%% at steady state: a rough bearing or a loose rotor",
                    rough * 100)));
      }
    }
    double worst = 0;
    for (StepResult s : all) {
      double agree = s.encoderAgreement();
      if (Double.isFinite(agree)) {
        worst = Math.max(worst, Math.abs(agree - 1.0));
      }
    }
    if (worst > 0) {
      metrics.put("encoderError", worst);
      Level level = band(worst, Limits.ENCODER_WARN, Limits.ENCODER_FAIL);
      if (level != null) {
        flags.add(
            new Flag(
                level,
                "EncoderDisagrees",
                String.format(
                    "Encoder counted %.1f%% more or fewer rotations than its velocity implies",
                    worst * 100)));
      }
    }
  }

  private static void coast(Inputs in, List<Flag> flags, Map<String, Double> metrics) {
    if (!Double.isFinite(in.coastHalfS())) {
      return;
    }
    metrics.put("coastHalfS", in.coastHalfS());
    if (in.fleet() == null || !Double.isFinite(in.fleet().coastHalfS())) {
      return;
    }
    double ratio = in.coastHalfS() / in.fleet().coastHalfS();
    metrics.put("coastVsFleet", ratio);
    Level level = null;
    if (ratio < Limits.COAST_VS_FLEET_FAIL) {
      level = Level.FAIL;
    } else if (ratio < Limits.COAST_VS_FLEET_WARN) {
      level = Level.WARN;
    }
    if (level != null) {
      flags.add(
          new Flag(
              level,
              "ShortCoast",
              String.format(
                  "Coasts to half speed in %.2f s, %.0f%% of the %.2f s fleet median: mechanical"
                      + " drag",
                  in.coastHalfS(), ratio * 100, in.fleet().coastHalfS())));
    }
  }

  private static void temperature(Inputs in, List<Flag> flags, Map<String, Double> metrics) {
    if (!Double.isFinite(in.tempStartC()) || !Double.isFinite(in.tempEndC())) {
      return;
    }
    double rise = in.tempEndC() - in.tempStartC();
    metrics.put("tempStartC", in.tempStartC());
    metrics.put("tempRiseC", rise);
    if (rise > Limits.TEMP_RISE_WARN_C) {
      flags.add(
          new Flag(
              Level.WARN,
              "HeatsUp",
              String.format("Warmed %.1f C in one unloaded run: internal friction", rise)));
    }
  }

  private static void faults(Inputs in, List<Flag> flags) {
    for (String f : in.faultsDuringTest()) {
      Level level = FAIL_FAULTS.contains(f) ? Level.FAIL : Level.WARN;
      flags.add(new Flag(level, "Fault" + f, faultMessage(f, true)));
    }
    for (String f : in.stickyBefore()) {
      Level level = WARN_STICKY.contains(f) ? Level.WARN : Level.INFO;
      flags.add(new Flag(level, "Sticky" + f, faultMessage(f, false)));
    }
  }

  private static String faultMessage(String fault, boolean live) {
    String what;
    switch (fault) {
      case "Hardware":
        what = "hardware fault";
        break;
      case "RotorFault1":
      case "RotorFault2":
        what = "rotor fault (" + fault + ")";
        break;
      case "BootDuringEnable":
        what = "rebooted while enabled: power leads, connector, or the controller itself";
        break;
      case "StatorCurrLimit":
        what = "hit the stator current limit while spinning free: binding";
        break;
      case "SupplyCurrLimit":
        what = "hit the supply current limit while spinning free";
        break;
      case "BridgeBrownout":
      case "Undervoltage":
      case "UnstableSupplyV":
        what = fault + ": supply sagged; check the jig battery and leads first";
        break;
      default:
        what = fault;
    }
    return live ? "During the test: " + what : "Sticky on arrival (history): " + what;
  }

  // ---------------------------------------------------------------- helpers

  /** Least-squares velocity vs volts: {slope, intercept}. */
  static double[] fit(List<StepResult> steps) {
    double n = steps.size();
    double sx = 0;
    double sy = 0;
    double sxx = 0;
    double sxy = 0;
    for (StepResult s : steps) {
      sx += s.volts();
      sy += s.meanVelRps();
      sxx += s.volts() * s.volts();
      sxy += s.volts() * s.meanVelRps();
    }
    double denom = n * sxx - sx * sx;
    double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
    return new double[] {slope, (sy - slope * sx) / n};
  }

  private static Level band(double value, double warn, double fail) {
    if (value > fail) {
      return Level.FAIL;
    }
    if (value > warn) {
      return Level.WARN;
    }
    return null;
  }
}
