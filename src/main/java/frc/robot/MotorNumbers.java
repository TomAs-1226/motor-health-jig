package frc.robot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The team's own number for each motor: #1, #2, #3... A number belongs to a serial number for good,
 * so it can go on a sticker.
 *
 * <p>Numbers come from two places, and nothing else has to be kept in sync: the seed file deployed
 * with the program ({@code src/main/deploy/motor-numbers.json}, for motors numbered by hand), and
 * the {@code motorNumber} in every record already on the jig. A serial seen in neither gets the next
 * number after the highest in use.
 */
final class MotorNumbers {
  private MotorNumbers() {}

  static final String SEED_FILE = "motor-numbers.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Serial to number, from the seed (which wins) and the records. */
  static Map<String, Integer> known(Path seedFile, Collection<Map<String, Object>> records) {
    Map<String, Integer> numbers = new HashMap<>();
    for (Map<String, Object> r : records) {
      String serial = Json.str(Json.obj(r, "motor"), "serial");
      double n = Json.num(r, "motorNumber");
      if (!serial.isEmpty() && Double.isFinite(n) && n >= 1) {
        numbers.put(serial, (int) n);
      }
    }
    numbers.putAll(readSeed(seedFile));
    return numbers;
  }

  /** This serial's number, or the next free one. Null when the serial is unknown. */
  static Integer numberFor(String serial, Map<String, Integer> known) {
    if (serial == null || serial.isEmpty()) {
      return null;
    }
    Integer n = known.get(serial);
    if (n != null) {
      return n;
    }
    int max = 0;
    for (int v : known.values()) {
      max = Math.max(max, v);
    }
    return max + 1;
  }

  /** {@code {"motors": [{"number": 1, "serial": "..."}]}}. A missing or broken file is no seed. */
  static Map<String, Integer> readSeed(Path file) {
    Map<String, Integer> out = new HashMap<>();
    if (file == null || !Files.exists(file)) {
      return out;
    }
    try {
      Map<String, Object> doc =
          MAPPER.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
      if (doc.get("motors") instanceof List<?> list) {
        for (Object o : list) {
          if (o instanceof Map<?, ?> m) {
            Object serial = m.get("serial");
            Object number = m.get("number");
            if (serial != null && number instanceof Number num && num.intValue() >= 1) {
              out.put(serial.toString().trim(), num.intValue());
            }
          }
        }
      }
    } catch (IOException e) {
      System.err.println("[MotorHealth] Could not read " + file + ": " + e);
    }
    return out;
  }
}
