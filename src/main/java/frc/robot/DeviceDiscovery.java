package frc.robot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks the Phoenix diagnostic server what is on the CAN bus, on a background thread.
 *
 * <p>Ported from Catalyst 2.0's {@code MotorHistory} discovery: the same endpoint and the same
 * fields. It is the only way to read a CTRE device's serial number from robot code, and it also
 * sees devices this program never constructed, so a motor with any CAN id (a new one is id 0) is
 * found without configuring anything.
 */
public final class DeviceDiscovery {
  /** One device as the diagnostic server describes it. */
  public record DeviceInfo(
      String model,
      int id,
      String name,
      String serial,
      String bus,
      String firmware,
      String hardwareRev,
      String manufactured) {

    /** A Talon FX: Falcon 500, Kraken X60 or Kraken X44. Talon FXS is a different class. */
    public boolean isTalonFx() {
      String m = model.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
      if (m.contains("talonfxs")) {
        return false;
      }
      return m.contains("talonfx") || m.contains("falcon") || m.contains("kraken");
    }

    /** Serial if known, otherwise bus and id. */
    public String key() {
      return serial.isEmpty() ? bus + ":" + id : serial;
    }

    public String describe() {
      return String.format(
          "%s id %d%s%s",
          model,
          id,
          serial.isEmpty() ? "" : " SN " + serial,
          name.isEmpty() ? "" : " \"" + name + "\"");
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final double RESCAN_SECONDS = 3.0;

  private final String server;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final AtomicReference<List<DeviceInfo>> latest = new AtomicReference<>(List.of());
  private volatile String status = "not scanned yet";
  private volatile long lastSuccessMs = 0;

  public DeviceDiscovery(String server) {
    this.server = server;
  }

  /** Start scanning every few seconds on a daemon thread. */
  public void start() {
    Thread t =
        new Thread(
            () -> {
              while (true) {
                scanOnce();
                try {
                  Thread.sleep((long) (RESCAN_SECONDS * 1000));
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "MotorHealth discovery");
    t.setDaemon(true);
    t.start();
  }

  /** The device list from the newest successful scan. */
  public List<DeviceInfo> latest() {
    return latest.get();
  }

  /** "ok, 2 devices" or why the last scan failed. */
  public String status() {
    return status;
  }

  /** Milliseconds since the last scan that worked, or a large number if none has. */
  public long msSinceSuccess() {
    return lastSuccessMs == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastSuccessMs;
  }

  private void scanOnce() {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(server + "?action=getdevices"))
              .timeout(Duration.ofSeconds(4))
              .GET()
              .build();
      String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
      List<DeviceInfo> devices = parse(body);
      latest.set(devices);
      lastSuccessMs = System.currentTimeMillis();
      status = "ok, " + devices.size() + " device" + (devices.size() == 1 ? "" : "s");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      status = "diagnostic server: " + e.getClass().getSimpleName();
    }
  }

  /** The server's {@code getdevices} reply as {@link DeviceInfo}s. */
  public static List<DeviceInfo> parse(String json) throws IOException {
    List<DeviceInfo> out = new ArrayList<>();
    JsonNode arr = MAPPER.readTree(json).get("DeviceArray");
    if (arr == null || !arr.isArray()) {
      return out;
    }
    for (JsonNode d : arr) {
      out.add(
          new DeviceInfo(
              text(d, "Model"),
              d.path("ID").asInt(-1),
              text(d, "Name"),
              text(d, "SerialNo"),
              text(d, "CANbus"),
              text(d, "CurrentVers"),
              text(d, "HardwareRev"),
              text(d, "ManDate")));
    }
    return out;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asText().trim();
  }
}
