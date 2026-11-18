package frc.robot;

import java.util.Map;

/** Small helpers for the record maps. */
final class Json {
  private Json() {}

  static double round(double v, int places) {
    double p = Math.pow(10, places);
    return Math.round(v * p) / p;
  }

  /** {@code m[a][b]...} as a map, or null. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Map<String, Object> m, String... path) {
    Object cur = m;
    for (String k : path) {
      if (!(cur instanceof Map<?, ?> map)) {
        return null;
      }
      cur = map.get(k);
    }
    return cur instanceof Map<?, ?> ? (Map<String, Object>) cur : null;
  }

  static String str(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  /** A number, or NaN if absent or not a number. */
  static double num(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v instanceof Number n ? n.doubleValue() : Double.NaN;
  }
}
