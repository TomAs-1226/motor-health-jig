package frc.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery parsing, specs, the fleet baseline and the files on disk. */
class RecordsTest {
  @Test
  void parsesDiagnosticServerDeviceList() throws Exception {
    String json =
        "{\"DeviceArray\":["
            + "{\"Model\":\"Talon FX\",\"ID\":0,\"Name\":\"Talon FX (Device ID 0)\","
            + "\"SerialNo\":\"5A3C2B1F0E9D8C7B\",\"CANbus\":\"rio\",\"CurrentVers\":\"26.1.0.0\","
            + "\"HardwareRev\":\"1.4\",\"ManDate\":\"Jan 9, 2025\"},"
            + "{\"Model\":\"CANcoder vers. H\",\"ID\":3,\"SerialNo\":\"1122\",\"CANbus\":\"rio\"},"
            + "{\"Model\":\"Talon FXS\",\"ID\":4,\"SerialNo\":\"9988\",\"CANbus\":\"rio\"}"
            + "],\"GeneralReturn\":{\"Error\":0}}";
    List<DeviceDiscovery.DeviceInfo> d = DeviceDiscovery.parse(json);
    assertEquals(3, d.size());
    assertEquals("5A3C2B1F0E9D8C7B", d.get(0).serial());
    assertEquals("26.1.0.0", d.get(0).firmware());
    assertTrue(d.get(0).isTalonFx());
    assertFalse(d.get(1).isTalonFx());
    assertFalse(d.get(2).isTalonFx(), "Talon FXS needs a different Phoenix class");
    assertEquals("", d.get(1).name());
  }

  @Test
  void emptyOrOddRepliesGiveNoDevices() throws Exception {
    assertTrue(DeviceDiscovery.parse("{}").isEmpty());
    assertTrue(DeviceDiscovery.parse("{\"DeviceArray\":null}").isEmpty());
  }

  @Test
  void specPrefersDatasheetThenReportedKv() {
    MotorSpec falcon = MotorSpec.lookup("Falcon500_Integrated", 9999);
    assertEquals(6380 / 60.0, falcon.freeSpeedRps(), 1e-9);
    assertEquals("datasheet", falcon.source());
    MotorSpec minion = MotorSpec.lookup("Minion_JST", 600); // RPM/V
    assertEquals(600 / 60.0 * 12, minion.freeSpeedRps(), 1e-9);
    assertTrue(Double.isNaN(MotorSpec.lookup("Unknown", 0).freeSpeedRps()));
    assertEquals(10.0, MotorSpec.kvRpsPerVolt(10.0), 1e-9);
  }

  private static Map<String, Object> record(
      String serial, String type, long at, String verdict, double kv, double amps, double coast) {
    Map<String, Object> motor = new LinkedHashMap<>();
    motor.put("serial", serial);
    motor.put("motorType", type);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("kvRpsPerV", kv);
    metrics.put("freeCurrentA", amps);
    metrics.put("coastHalfS", coast);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("verdict", verdict);
    result.put("metrics", metrics);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("testedAtMs", at);
    r.put("motor", motor);
    r.put("result", result);
    return r;
  }

  @Test
  void fleetUsesNewestHealthyRecordPerMotorAndNeedsThree() {
    String x60 = "KrakenX60_Integrated";
    List<Map<String, Object>> rs = new ArrayList<>();
    rs.add(record("A", x60, 1, "GOOD", 7.0, 9.0, 1.0)); // superseded by A's newer test
    rs.add(record("A", x60, 2, "GOOD", 8.0, 2.0, 4.0));
    rs.add(record("B", x60, 1, "WATCH", 8.2, 2.2, 4.2));
    rs.add(record("C", x60, 1, "FAIL", 5.0, 9.0, 0.5)); // failed motors are not the baseline
    rs.add(record("D", "Falcon500_Integrated", 1, "GOOD", 8.8, 1.5, 3.0));
    assertNull(FleetBaseline.of(x60, "", rs), "two healthy X60s is too few");

    rs.add(record("E", x60, 1, "GOOD", 8.1, 2.1, 4.1));
    FleetBaseline.Stats s = FleetBaseline.of(x60, "", rs);
    assertEquals(3, s.motors());
    assertEquals(8.1, s.kvRpsPerV(), 1e-9);
    assertEquals(2.1, s.freeCurrentA(), 1e-9);
    assertNull(FleetBaseline.of(x60, "E", rs), "a motor is never its own baseline");
  }

  @Test
  void savesLoadsAndLogs(@TempDir Path dir) throws Exception {
    RecordStore store = new RecordStore(dir);
    store.save(record("A", "KrakenX60_Integrated", 5, "GOOD", 8, 2, 4), "20261117-120000_SN-A");
    store.appendLog(List.of("t", "A", "0", "KrakenX60_Integrated", "26.1", "sticker, 7", "GOOD"));
    store.appendLog(List.of("t2", "B"));
    Files.writeString(store.testsDir().resolve("broken.json"), "{not json");

    List<Map<String, Object>> all = store.loadAll();
    assertEquals(1, all.size());
    assertEquals("A", Json.str(Json.obj(all.get(0), "motor"), "serial"));

    List<String> log = Files.readAllLines(dir.resolve("log.csv"));
    assertEquals(RecordStore.CSV_HEADER, log.get(0));
    assertEquals("t,A,0,KrakenX60_Integrated,26.1,\"sticker, 7\",GOOD", log.get(1));
    assertEquals(3, log.size());
    assertFalse(Files.exists(store.testsDir().resolve("20261117-120000_SN-A.json.tmp")));
  }

  @Test
  void clockFromAnUnsetRioIsNotTrusted() {
    // What X1's rio reported over SSH before any Driver Station connected.
    assertFalse(JigSession.clockTrusted(java.time.Instant.parse("2024-12-18T14:07:14Z")));
    assertTrue(JigSession.clockTrusted(java.time.Instant.parse("2026-11-18T03:31:54Z")));
    assertEquals(
        java.time.Instant.parse("2026-11-01T00:00:00Z").getEpochSecond(),
        JigSession.CLOCK_SANE_AFTER_EPOCH_S);
  }

  @Test
  void parsesTheRealX1DeviceList() throws Exception {
    // Shape and values as X1's rio served them on 2026-11-18 (names trimmed to two devices).
    String json =
        "{\"DeviceArray\":[{\"Model\":\"Talon FX vers. C\",\"ID\":11,\"Name\":\"Front Left Steer\","
            + "\"SerialNo\":\"000E173405DC0000001900010B00011A\",\"CANbus\":\"rio\","
            + "\"CurrentVers\":\"26.1.1.1 (Phoenix 6)\",\"ManDate\":\"July 1, 2024\"}]}";
    DeviceDiscovery.DeviceInfo d = DeviceDiscovery.parse(json).get(0);
    assertTrue(d.isTalonFx(), "\"Talon FX vers. C\" is a Talon FX");
    assertEquals(11, d.id());
    assertEquals("SN-000E173405DC0000001900010B00011A", RecordStore.fileSafe("SN-" + d.serial()));
  }

  @Test
  void fileNamesAreSafe() {
    assertEquals("SN-ab_cd_1", RecordStore.fileSafe("SN-ab/cd:1"));
  }
}
