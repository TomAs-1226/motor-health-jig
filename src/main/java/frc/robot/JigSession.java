package frc.robot;

import com.ctre.phoenix6.CANBus;
import com.ctre.phoenix6.hardware.TalonFX;
import edu.wpi.first.wpilibj.Filesystem;
import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Test-mode enable: find every Talon FX on the jig, run {@link MotorTestRun} on each in turn,
 * estimate its health and write the record.
 */
final class JigSession {
  static final String NT = "MotorHealth/";
  static final String SOFTWARE = "motor-health-jig 1.0";

  /** How long to wait for the diagnostic server before probing CAN ids instead. */
  static final double DISCOVERY_WAIT_S = 8.0;

  static final double PROBE_WAIT_S = 1.0;

  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  /** Phoenix objects are made once per CAN id for the life of the program. */
  private static final Map<String, TalonFX> MOTORS = new HashMap<>();

  private enum State {
    WAITING_FOR_DEVICES,
    PROBING,
    TESTING,
    FINISHED
  }

  private final DeviceDiscovery discovery;
  private final PowerDistribution pdh;
  private final RecordStore store;

  private State state = State.WAITING_FOR_DEVICES;
  private double stateStart;
  private final Deque<DeviceDiscovery.DeviceInfo> queue = new ArrayDeque<>();
  private List<DeviceDiscovery.DeviceInfo> otherDevices = List.of();
  private boolean serialsKnown = true;
  private MotorTestRun current;
  private int tested;
  private int total;

  JigSession(DeviceDiscovery discovery, PowerDistribution pdh, RecordStore store) {
    this.discovery = discovery;
    this.pdh = pdh;
    this.store = store;
  }

  void start(double now) {
    state = State.WAITING_FOR_DEVICES;
    stateStart = now;
    status("Looking for motors...");
    if (!clockTrusted(Instant.now())) {
      System.err.println(
          "[MotorHealth] The roboRIO clock reads " + Instant.now() + ": records will carry that"
              + " date with clockTrusted=false. The Driver Station sets the clock when it connects.");
    }
  }

  /**
   * True once the clock is past the day this jig was written. The roboRIO has no clock battery: it
   * boots at whatever date it last saved (X1's rio booted in December 2024) until the Driver
   * Station sets it, so "after 2023" is not a test of anything.
   */
  static boolean clockTrusted(Instant t) {
    return t.getEpochSecond() > CLOCK_SANE_AFTER_EPOCH_S;
  }

  /** 2026-11-01T00:00Z. */
  static final long CLOCK_SANE_AFTER_EPOCH_S = 1_793_491_200L;

  void update(double now) {
    switch (state) {
      case WAITING_FOR_DEVICES:
        waitForDevices(now);
        break;
      case PROBING:
        probe(now);
        break;
      case TESTING:
        test(now);
        break;
      default:
        break;
    }
  }

  /** Disabled or left Test mode: stop the motor, record nothing for the interrupted run. */
  void stopByOperator() {
    if (current != null && !current.isDone()) {
      current.abort("Stopped by operator", false);
      status("Stopped by operator. Nothing recorded for " + current.info().describe());
    }
    state = State.FINISHED;
  }

  // ---------------------------------------------------------------- states

  private void waitForDevices(double now) {
    // Only a scan finished after this enable: an older list could describe the motor just unplugged.
    boolean fresh = discovery.msSinceSuccess() < (now - stateStart) * 1000;
    List<DeviceDiscovery.DeviceInfo> all = discovery.latest();
    List<DeviceDiscovery.DeviceInfo> talons = new ArrayList<>();
    List<DeviceDiscovery.DeviceInfo> others = new ArrayList<>();
    for (DeviceDiscovery.DeviceInfo d : all) {
      (d.isTalonFx() ? talons : others).add(d);
    }
    if (fresh && !talons.isEmpty()) {
      talons.sort(Comparator.comparingInt(DeviceDiscovery.DeviceInfo::id));
      otherDevices = others;
      enqueue(talons, now);
      return;
    }
    if (now - stateStart >= DISCOVERY_WAIT_S) {
      otherDevices = others;
      serialsKnown = false;
      for (int id : JigConfig.FALLBACK_IDS) {
        motor(DeviceDiscovery.DeviceInfo.probed(id));
      }
      state = State.PROBING;
      stateStart = now;
      status("Diagnostic server gave no motors (" + discovery.status() + "); probing CAN ids...");
    }
  }

  private void probe(double now) {
    if (now - stateStart < PROBE_WAIT_S) {
      return;
    }
    List<DeviceDiscovery.DeviceInfo> found = new ArrayList<>();
    for (int id : JigConfig.FALLBACK_IDS) {
      DeviceDiscovery.DeviceInfo d = DeviceDiscovery.DeviceInfo.probed(id);
      if (motor(d).isConnected()) {
        found.add(d);
      }
    }
    if (found.isEmpty()) {
      state = State.FINISHED;
      status(
          "No Talon FX found. Check CAN wiring and termination, the motor's 12 V, and that its"
              + " firmware is Phoenix 6 (2026).");
      return;
    }
    enqueue(found, now);
  }

  private void enqueue(List<DeviceDiscovery.DeviceInfo> talons, double now) {
    queue.clear();
    queue.addAll(talons);
    total = talons.size();
    tested = 0;
    state = State.TESTING;
    stateStart = now;
  }

  private void test(double now) {
    if (current == null) {
      DeviceDiscovery.DeviceInfo next = queue.poll();
      if (next == null) {
        state = State.FINISHED;
        status(String.format("Done: %d motor%s tested. Disable, swap, enable.", tested, plural()));
        return;
      }
      current = new MotorTestRun(next, motor(next), pdh);
      current.begin(now);
    }
    current.update(now);
    SmartDashboard.putString(NT + "Current", current.info().describe());
    status(
        String.format(
            "Testing %d/%d: %s, %s",
            tested + 1, total, current.info().describe(), current.progress()));
    if (current.isDone()) {
      if (!current.operatorStopped()) {
        finish(current);
      }
      tested++;
      current = null;
    }
  }

  // ---------------------------------------------------------------- recording

  private void finish(MotorTestRun run) {
    DeviceDiscovery.DeviceInfo info = run.info();
    List<Map<String, Object>> history = store.loadAll();
    Integer number =
        MotorNumbers.numberFor(
            info.serial(),
            MotorNumbers.known(
                Filesystem.getDeployDirectory().toPath().resolve(MotorNumbers.SEED_FILE), history));
    String numberText = number == null ? "no number (serial unknown)" : "#" + number;
    MotorSpec spec = MotorSpec.lookup(run.motorType(), run.kvReported());
    FleetBaseline.Stats fleet = FleetBaseline.of(run.motorType(), info.serial(), history);
    HealthEstimator.Report report =
        HealthEstimator.estimate(
            new HealthEstimator.Inputs(
                spec,
                run.steps(),
                run.coastHalfS(),
                run.tempStart(),
                run.tempEnd(),
                run.faultsDuring(),
                run.stickyBefore(),
                run.abortReason(),
                fleet));

    Instant at = Instant.now();
    String label = SmartDashboard.getString(NT + "Label", "").trim();
    String notes = SmartDashboard.getString(NT + "Notes", "").trim();
    int previousTests = 0;
    for (Map<String, Object> r : history) {
      if (!info.serial().isEmpty() && info.serial().equals(Json.str(Json.obj(r, "motor"), "serial"))) {
        previousTests++;
      }
    }

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("format", "motor-health-jig");
    record.put("version", 1);
    record.put("motorNumber", number);
    record.put("testedAt", at.toString());
    record.put("testedAtMs", at.toEpochMilli());
    record.put("clockTrusted", clockTrusted(at));
    record.put("label", label);
    record.put("notes", notes);
    record.put("motor", motorJson(run, spec));
    record.put("result", report.toJson());
    record.put("previousTestsOfThisMotor", previousTests);
    List<Object> steps = new ArrayList<>();
    for (StepResult s : run.steps()) {
      steps.add(s.toJson());
    }
    record.put("steps", steps);
    Map<String, Object> coast = new LinkedHashMap<>();
    coast.put("halfSpeedS", finiteOrNull(run.coastHalfS()));
    coast.put("toTenPercentS", finiteOrNull(run.coastFullS()));
    record.put("coastDown", coast);
    Map<String, Object> faults = new LinkedHashMap<>();
    faults.put("stickyOnArrival", new ArrayList<>(run.stickyBefore()));
    faults.put("duringTest", new ArrayList<>(run.faultsDuring()));
    record.put("faults", faults);
    if (fleet != null) {
      Map<String, Object> f = new LinkedHashMap<>();
      f.put("motors", fleet.motors());
      f.put("kvRpsPerV", finiteOrNull(fleet.kvRpsPerV()));
      f.put("freeCurrentA", finiteOrNull(fleet.freeCurrentA()));
      f.put("coastHalfS", finiteOrNull(fleet.coastHalfS()));
      record.put("fleetBaseline", f);
    }
    List<Object> others = new ArrayList<>();
    for (DeviceDiscovery.DeviceInfo d : otherDevices) {
      others.add(deviceJson(d));
    }
    record.put("otherDevicesOnJig", others);
    record.put("jig", jigJson(run));

    String who = info.serial().isEmpty() ? "ID-" + info.id() : "SN-" + info.serial();
    String stem =
        FILE_TIME.format(at) + (number == null ? "" : "_M" + number) + "_" + RecordStore.fileSafe(who);
    String savedTo;
    try {
      Path file = store.save(record, stem);
      Map<String, Double> m = report.metrics();
      store.appendLog(
          List.of(
              at.toString(),
              number == null ? "" : number.toString(),
              info.serial(),
              Integer.toString(info.id()),
              run.motorType(),
              info.firmware(),
              label,
              report.verdict(),
              Integer.toString(report.score()),
              fmt(m.get("kvRpsPerV")),
              fmt(m.get("projectedFreeRpm12V")),
              fmt(m.get("freeCurrentA")),
              fmt(m.get("coastHalfS")),
              fmt(m.get("tempRiseC")),
              report.summary()));
      savedTo = file.toString();
    } catch (Exception e) {
      savedTo = "NOT SAVED: " + e;
      System.err.println("[MotorHealth] Could not save the record: " + e);
    }

    SmartDashboard.putString(
        NT + "Last/Number", number == null ? "none" : "#" + number + " (write it on the motor)");
    SmartDashboard.putString(
        NT + "Last/Motor", numberText + ": " + info.describe() + " (" + run.motorType() + ")");
    SmartDashboard.putString(NT + "Last/Serial", info.serial().isEmpty() ? "unknown" : info.serial());
    SmartDashboard.putNumber(NT + "Last/CanId", info.id());
    SmartDashboard.putString(NT + "Last/Verdict", report.verdict());
    SmartDashboard.putNumber(NT + "Last/Score", report.score());
    SmartDashboard.putString(NT + "Last/Summary", report.summary());
    SmartDashboard.putString(NT + "Last/File", savedTo);
    List<String> lines = new ArrayList<>();
    for (HealthEstimator.Flag f : report.flags()) {
      lines.add(f.level() + " " + f.code() + ": " + f.message());
    }
    SmartDashboard.putStringArray(NT + "Last/Flags", lines.toArray(new String[0]));

    System.out.printf(
        "[MotorHealth] %s %s %s (%s): %s, score %d. %s%n  -> %s%n",
        report.verdict(),
        numberText,
        info.describe(),
        run.motorType(),
        report.summary(),
        report.score(),
        String.join(" | ", lines),
        savedTo);
  }

  private Map<String, Object> motorJson(MotorTestRun run, MotorSpec spec) {
    DeviceDiscovery.DeviceInfo info = run.info();
    Map<String, Object> m = deviceJson(info);
    m.put("serialSource", serialsKnown ? "Phoenix diagnostic server" : "unavailable (CAN id probe)");
    m.put("motorType", run.motorType());
    m.put("kvReported", finiteOrNull(run.kvReported()));
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("freeSpeedRpm", finiteOrNull(spec.freeSpeedRps() * 60));
    s.put("freeCurrentA", finiteOrNull(spec.freeCurrentA()));
    s.put("source", spec.source());
    m.put("spec", s);
    return m;
  }

  private static Map<String, Object> deviceJson(DeviceDiscovery.DeviceInfo d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("serial", d.serial());
    m.put("canId", d.id());
    m.put("model", d.model());
    m.put("name", d.name());
    m.put("bus", d.bus());
    m.put("firmware", d.firmware());
    m.put("hardwareRev", d.hardwareRev());
    m.put("bootloaderRev", d.bootloaderRev());
    m.put("manufactured", d.manufactured());
    m.put("proLicensed", d.proLicensed());
    m.put("licenses", d.licenses());
    return m;
  }

  private Map<String, Object> jigJson(MotorTestRun run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("software", SOFTWARE);
    m.put("team", JigConfig.TEAM);
    m.put("roboRioSerial", RobotController.getSerialNumber());
    m.put("pdhCanId", pdh == null ? null : JigConfig.PDH_CAN_ID);
    m.put("pdhBusV", pdh == null ? null : Json.round(pdh.getVoltage(), 2));
    m.put("configApply", run.configStatus());
    m.put("stepVolts", MotorTestRun.STEP_VOLTS);
    m.put("settleS", MotorTestRun.SETTLE_S);
    m.put("sampleS", MotorTestRun.SAMPLE_S);
    return m;
  }

  // ---------------------------------------------------------------- helpers

  private TalonFX motor(DeviceDiscovery.DeviceInfo d) {
    String bus = d.bus().isEmpty() || d.bus().equalsIgnoreCase("can0") ? "rio" : d.bus();
    return MOTORS.computeIfAbsent(
        bus + ":" + d.id(),
        k -> new TalonFX(d.id(), bus.equals("rio") ? CANBus.roboRIO() : new CANBus(bus)));
  }

  private static void status(String s) {
    SmartDashboard.putString(NT + "Status", s);
  }

  private String plural() {
    return tested == 1 ? "" : "s";
  }

  private static Object finiteOrNull(double v) {
    return Double.isFinite(v) ? Json.round(v, 4) : null;
  }

  private static String fmt(Double v) {
    return v == null ? "" : String.format("%.3f", v);
  }
}
