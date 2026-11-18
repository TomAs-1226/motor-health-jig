package frc.robot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The typical motor of a type, from the records already on the jig.
 *
 * <p>A datasheet number is the same for every team's bench; the fleet median is this jig, this
 * battery, this sampling. Comparing against it cancels most of the jig's own error, so its
 * thresholds are tighter than the datasheet ones. It needs {@link #MIN_MOTORS} distinct healthy
 * motors of the type before it says anything.
 */
public final class FleetBaseline {
  private FleetBaseline() {}

  public static final int MIN_MOTORS = 3;

  /**
   * Medians over the fleet. NaN where no motor has the metric.
   *
   * @param motors how many distinct motors the medians come from
   */
  public record Stats(
      String motorType, int motors, double kvRpsPerV, double freeCurrentA, double coastHalfS) {}

  /**
   * The baseline for {@code motorType}, from each motor's newest record that did not fail, leaving
   * out {@code excludeSerial} so a motor is never compared with itself. Null if too few motors.
   */
  public static Stats of(
      String motorType, String excludeSerial, Collection<Map<String, Object>> records) {
    Map<String, Map<String, Object>> newest = new HashMap<>();
    for (Map<String, Object> r : records) {
      Map<String, Object> motor = Json.obj(r, "motor");
      Map<String, Object> result = Json.obj(r, "result");
      String serial = Json.str(motor, "serial");
      if (serial.isEmpty()
          || serial.equals(excludeSerial)
          || !Json.str(motor, "motorType").equals(motorType)
          || result == null) {
        continue;
      }
      Map<String, Object> prev = newest.get(serial);
      if (prev == null || Json.num(r, "testedAtMs") > Json.num(prev, "testedAtMs")) {
        newest.put(serial, r);
      }
    }
    List<Double> kv = new ArrayList<>();
    List<Double> current = new ArrayList<>();
    List<Double> coast = new ArrayList<>();
    int motors = 0;
    for (Map<String, Object> r : newest.values()) {
      if (Json.str(Json.obj(r, "result"), "verdict").equals(HealthEstimator.FAIL)) {
        continue;
      }
      motors++;
      Map<String, Object> metrics = Json.obj(r, "result", "metrics");
      add(kv, Json.num(metrics, "kvRpsPerV"));
      add(current, Json.num(metrics, "freeCurrentA"));
      add(coast, Json.num(metrics, "coastHalfS"));
    }
    if (motors < MIN_MOTORS) {
      return null;
    }
    return new Stats(motorType, motors, median(kv), median(current), median(coast));
  }

  private static void add(List<Double> list, double v) {
    if (Double.isFinite(v)) {
      list.add(v);
    }
  }

  static double median(List<Double> values) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(a);
    int mid = a.length / 2;
    return a.length % 2 == 1 ? a[mid] : (a[mid - 1] + a[mid]) / 2.0;
  }
}
