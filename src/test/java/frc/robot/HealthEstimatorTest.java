package frc.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HealthEstimatorTest {
  private static final MotorSpec X60 = MotorSpec.lookup("KrakenX60_Integrated", Double.NaN);

  /** A step at {@code v} volts from a motor turning {@code rpsPerV} with {@code amps} free current. */
  private static StepResult step(double v, double rpsPerV, double amps) {
    double vel = v * rpsPerV;
    return new StepResult(
        v, 50, 0.98, vel, Math.abs(vel) * 0.005, amps, amps * Math.abs(v) / 12.5, 12.5,
        vel * 0.98, new double[] {0.05, amps * Math.abs(v) / 12.5, 0.02});
  }

  /** A healthy Kraken X60: 8.1 rps/V forward (97% of spec), 1.9 A, symmetric. */
  private static List<StepResult> healthy() {
    List<StepResult> s = new ArrayList<>();
    s.add(step(3, 8.1, 1.7));
    s.add(step(6, 8.1, 1.8));
    s.add(step(9, 8.1, 1.9));
    s.add(step(-6, 8.1, 1.8));
    return s;
  }

  private static HealthEstimator.Inputs inputs(
      List<StepResult> steps, Set<String> during, Set<String> sticky, FleetBaseline.Stats fleet) {
    return new HealthEstimator.Inputs(X60, steps, 1.2, 25, 27, during, sticky, null, fleet);
  }

  private static boolean has(HealthEstimator.Report r, String code, HealthEstimator.Level level) {
    return r.flags().stream().anyMatch(f -> f.code().equals(code) && f.level() == level);
  }

  @Test
  void healthyMotorIsGood() {
    HealthEstimator.Report r =
        HealthEstimator.estimate(inputs(healthy(), Set.of(), Set.of(), null));
    assertEquals(HealthEstimator.GOOD, r.verdict(), r.flags().toString());
    assertEquals(100, r.score());
    assertEquals(8.1, r.metrics().get("kvRpsPerV"), 1e-9);
    assertEquals(8.1 * 12 * 60, r.metrics().get("projectedFreeRpm12V"), 1e-6);
    assertEquals(1.0, r.metrics().get("pdhChannel"));
  }

  @Test
  void slowMotorFailsAgainstSpec() {
    List<StepResult> s = new ArrayList<>();
    for (StepResult h : healthy()) {
      s.add(step(h.volts(), 6.8, 1.9)); // 82% of spec
    }
    HealthEstimator.Report r = HealthEstimator.estimate(inputs(s, Set.of(), Set.of(), null));
    assertTrue(has(r, "SlowVsSpec", HealthEstimator.Level.FAIL), r.flags().toString());
    assertEquals(HealthEstimator.FAIL, r.verdict());
  }

  @Test
  void highFreeCurrentWarnsThenFails() {
    List<StepResult> warn = healthy();
    warn.set(2, step(9, 8.1, 3.6)); // 1.8x of 2 A
    assertTrue(
        has(
            HealthEstimator.estimate(inputs(warn, Set.of(), Set.of(), null)),
            "HighFreeCurrent",
            HealthEstimator.Level.WARN));
    List<StepResult> fail = healthy();
    fail.set(2, step(9, 8.1, 5.5)); // 2.75x
    assertTrue(
        has(
            HealthEstimator.estimate(inputs(fail, Set.of(), Set.of(), null)),
            "HighFreeCurrent",
            HealthEstimator.Level.FAIL));
  }

  @Test
  void reverseSlowerThanForwardIsAsymmetric() {
    List<StepResult> s = healthy();
    s.set(3, step(-6, 7.4, 1.8)); // ~9% slower in reverse
    HealthEstimator.Report r = HealthEstimator.estimate(inputs(s, Set.of(), Set.of(), null));
    assertTrue(has(r, "Asymmetric", HealthEstimator.Level.WARN), r.flags().toString());
    assertEquals(HealthEstimator.WATCH, r.verdict());
  }

  @Test
  void hardwareFaultDuringRunFails() {
    HealthEstimator.Report r =
        HealthEstimator.estimate(inputs(healthy(), Set.of("Hardware"), Set.of(), null));
    assertTrue(has(r, "FaultHardware", HealthEstimator.Level.FAIL));
    assertEquals(HealthEstimator.FAIL, r.verdict());
  }

  @Test
  void stickyHistoryIsInfoUnlessSerious() {
    HealthEstimator.Report r =
        HealthEstimator.estimate(
            inputs(healthy(), Set.of(), Set.of("Undervoltage", "RotorFault1"), null));
    assertTrue(has(r, "StickyUndervoltage", HealthEstimator.Level.INFO));
    assertTrue(has(r, "StickyRotorFault1", HealthEstimator.Level.WARN));
    assertEquals(HealthEstimator.WATCH, r.verdict());
  }

  @Test
  void fleetCatchesShortCoastAndDrift() {
    FleetBaseline.Stats fleet =
        new FleetBaseline.Stats("KrakenX60_Integrated", 5, 8.1 * 1.07, 1.9, 4.0);
    HealthEstimator.Report r =
        HealthEstimator.estimate(inputs(healthy(), Set.of(), Set.of(), fleet));
    // 1.2 s coast vs 4.0 s median = 30%: drag
    assertTrue(has(r, "ShortCoast", HealthEstimator.Level.FAIL), r.flags().toString());
    // 6.5% under the fleet's Kv
    assertTrue(has(r, "SlowVsFleet", HealthEstimator.Level.WARN), r.flags().toString());
  }

  @Test
  void abortedRunFails() {
    HealthEstimator.Report r =
        HealthEstimator.estimate(
            new HealthEstimator.Inputs(
                X60, List.of(), Double.NaN, 25, 25, Set.of(), Set.of(), "Did not spin", null));
    assertEquals(HealthEstimator.FAIL, r.verdict());
    assertTrue(r.score() < 60);
  }

  @Test
  void unknownMotorWithoutFleetSaysSo() {
    MotorSpec unknown = MotorSpec.lookup("CustomBrushless", Double.NaN);
    HealthEstimator.Report r =
        HealthEstimator.estimate(
            new HealthEstimator.Inputs(
                unknown, healthy(), 1.2, 25, 26, Set.of(), Set.of(), null, null));
    assertTrue(has(r, "NoReference", HealthEstimator.Level.INFO));
    assertFalse(r.metrics().containsKey("speedVsSpec"));
  }

  @Test
  void encoderThatDropsCountsIsFlagged() {
    List<StepResult> s = healthy();
    StepResult h = s.get(1);
    s.set(
        1,
        new StepResult(
            h.volts(), h.samples(), h.durationS(), h.meanVelRps(), h.stdVelRps(), h.meanStatorA(),
            h.meanSupplyA(), h.meanBusV(), h.positionDeltaRot() * 0.8, h.pdhMeanA()));
    HealthEstimator.Report r = HealthEstimator.estimate(inputs(s, Set.of(), Set.of(), null));
    assertTrue(has(r, "EncoderDisagrees", HealthEstimator.Level.FAIL), r.flags().toString());
  }

  @Test
  void fitRecoversSlopeAndIntercept() {
    double[] f = HealthEstimator.fit(List.of(step(3, 8, 1), step(6, 8, 1), step(9, 8, 1)));
    assertEquals(8.0, f[0], 1e-9);
    assertEquals(0.0, f[1], 1e-9);
  }
}
