package frc.robot;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CurrentLimitsConfigs;
import com.ctre.phoenix6.configs.MotorOutputConfigs;
import com.ctre.phoenix6.configs.OpenLoopRampsConfigs;
import com.ctre.phoenix6.configs.VoltageConfigs;
import com.ctre.phoenix6.controls.CoastOut;
import com.ctre.phoenix6.controls.NeutralOut;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.ConnectedMotorValue;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import edu.wpi.first.wpilibj.PowerDistribution;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One motor's bench run, a step per robot loop. The motor must be free to spin: nothing on the
 * shaft.
 *
 * <pre>
 * prepare 1 s (neutral; read sticky faults, then clear them)
 *   +3 V, +6 V, +9 V   each: settle 1.5 s, average 1.0 s
 *   coast from +9 V    time to half speed
 *   -6 V               settle, average (direction asymmetry)
 *   coast to stop
 * </pre>
 *
 * <p>About 15 s per motor. It stops early, and says why, if the motor will not spin, draws binding
 * current, or drops off the bus. Disabling the robot stops it immediately.
 */
final class MotorTestRun {
  static final double PREPARE_S = 1.0;
  static final double SETTLE_S = 1.5;
  static final double SAMPLE_S = 1.0;
  static final double COAST_TIMEOUT_S = 10.0;
  static final double STOP_TIMEOUT_S = 8.0;
  static final double[] STEP_VOLTS = {3, 6, 9, -6};
  /** The coast-down is measured from the end of this step. */
  static final int COAST_AFTER_STEP = 2;

  static final double STATOR_LIMIT_A = 40;
  static final double SUPPLY_LIMIT_A = 30;
  /** Unloaded, any motor this far over its free current is binding. */
  static final double BINDING_A = 35;

  static final double BINDING_S = 0.5;
  /** Every FRC brushless motor turns well over 4 rps per volt free; under 1 is not spinning. */
  static final double MIN_RPS_PER_VOLT = 1.0;

  private enum Phase {
    PREPARE,
    SETTLE,
    SAMPLE,
    COAST,
    STOPPING,
    DONE
  }

  private record FaultSignal(
      String name, StatusSignal<Boolean> live, StatusSignal<Boolean> sticky) {}

  private final DeviceDiscovery.DeviceInfo info;
  private final TalonFX motor;
  private final PowerDistribution pdh;

  private final StatusSignal<?> velocity;
  private final StatusSignal<?> position;
  private final StatusSignal<?> stator;
  private final StatusSignal<?> supply;
  private final StatusSignal<?> busVolts;
  private final StatusSignal<?> temp;
  private final StatusSignal<ConnectedMotorValue> connectedMotor;
  private final StatusSignal<?> motorKv;
  private final List<FaultSignal> faults;
  private final List<BaseStatusSignal> loopSignals = new ArrayList<>();

  private final VoltageOut voltage = new VoltageOut(0);
  private final CoastOut coastOut = new CoastOut();
  private final NeutralOut neutralOut = new NeutralOut();

  private Phase phase = Phase.PREPARE;
  private double phaseStart;
  private int stepIndex;
  private StepResult.Accumulator acc;
  private double coastStartVel;
  private double highCurrentSince = Double.NaN;

  private final List<StepResult> steps = new ArrayList<>();
  private final Set<String> stickyBefore = new TreeSet<>();
  private final Set<String> faultsDuring = new TreeSet<>();
  private double tempStart = Double.NaN;
  private double tempEnd = Double.NaN;
  private double coastHalfS = Double.NaN;
  private double coastFullS = Double.NaN;
  private String motorType = "Unknown";
  private double kvReported = Double.NaN;
  private String abortReason;
  private boolean operatorStopped;
  private String configStatus = "";

  MotorTestRun(DeviceDiscovery.DeviceInfo info, TalonFX motor, PowerDistribution pdh) {
    this.info = info;
    this.motor = motor;
    this.pdh = pdh;
    // Rotor signals, not mechanism ones: a motor off a robot may carry a gear ratio in its config.
    velocity = motor.getRotorVelocity(false);
    position = motor.getRotorPosition(false);
    stator = motor.getStatorCurrent(false);
    supply = motor.getSupplyCurrent(false);
    busVolts = motor.getSupplyVoltage(false);
    temp = motor.getDeviceTemp(false);
    connectedMotor = motor.getConnectedMotor(false);
    motorKv = motor.getMotorKV(false);
    faults =
        List.of(
            new FaultSignal(
                "Hardware", motor.getFault_Hardware(false), motor.getStickyFault_Hardware(false)),
            new FaultSignal(
                "RotorFault1",
                motor.getFault_RotorFault1(false),
                motor.getStickyFault_RotorFault1(false)),
            new FaultSignal(
                "RotorFault2",
                motor.getFault_RotorFault2(false),
                motor.getStickyFault_RotorFault2(false)),
            new FaultSignal(
                "BootDuringEnable",
                motor.getFault_BootDuringEnable(false),
                motor.getStickyFault_BootDuringEnable(false)),
            new FaultSignal(
                "StatorCurrLimit",
                motor.getFault_StatorCurrLimit(false),
                motor.getStickyFault_StatorCurrLimit(false)),
            new FaultSignal(
                "SupplyCurrLimit",
                motor.getFault_SupplyCurrLimit(false),
                motor.getStickyFault_SupplyCurrLimit(false)),
            new FaultSignal(
                "BridgeBrownout",
                motor.getFault_BridgeBrownout(false),
                motor.getStickyFault_BridgeBrownout(false)),
            new FaultSignal(
                "Undervoltage",
                motor.getFault_Undervoltage(false),
                motor.getStickyFault_Undervoltage(false)),
            new FaultSignal(
                "UnstableSupplyV",
                motor.getFault_UnstableSupplyV(false),
                motor.getStickyFault_UnstableSupplyV(false)),
            new FaultSignal(
                "OverSupplyV",
                motor.getFault_OverSupplyV(false),
                motor.getStickyFault_OverSupplyV(false)),
            new FaultSignal(
                "DeviceTemp",
                motor.getFault_DeviceTemp(false),
                motor.getStickyFault_DeviceTemp(false)),
            new FaultSignal(
                "ProcTemp", motor.getFault_ProcTemp(false), motor.getStickyFault_ProcTemp(false)));

    Collections.addAll(loopSignals, velocity, position, stator, supply, busVolts, temp);
    for (FaultSignal f : faults) {
      loopSignals.add(f.live());
    }

    voltage.EnableFOC = false;
    voltage.IgnoreHardwareLimits = true;
    voltage.IgnoreSoftwareLimits = true;
  }

  /** Configure the motor and start the run. */
  void begin(double now) {
    BaseStatusSignal.setUpdateFrequencyForAll(100, velocity, position, stator, supply, busVolts);
    List<BaseStatusSignal> slow = new ArrayList<>(List.of(temp, connectedMotor, motorKv));
    for (FaultSignal f : faults) {
      slow.add(f.live());
      slow.add(f.sticky());
    }
    BaseStatusSignal.setUpdateFrequencyForAll(10, slow);

    // Only the groups the run depends on are rewritten. Robot code re-applies its own at boot.
    var out = new MotorOutputConfigs();
    out.NeutralMode = NeutralModeValue.Coast;
    out.Inverted = InvertedValue.CounterClockwise_Positive;
    var limits = new CurrentLimitsConfigs();
    limits.StatorCurrentLimit = STATOR_LIMIT_A;
    limits.StatorCurrentLimitEnable = true;
    limits.SupplyCurrentLimit = SUPPLY_LIMIT_A;
    limits.SupplyCurrentLimitEnable = true;
    var ramps = new OpenLoopRampsConfigs();
    ramps.VoltageOpenLoopRampPeriod = 0.25;
    var cfg = motor.getConfigurator();
    String status =
        cfg.apply(out, 0.25).getName()
            + "/"
            + cfg.apply(limits, 0.25).getName()
            + "/"
            + cfg.apply(ramps, 0.25).getName()
            + "/"
            + cfg.apply(new VoltageConfigs(), 0.25).getName();
    configStatus = status;
    motor.setControl(neutralOut);
    enter(Phase.PREPARE, now);
  }

  /** Advance one loop. */
  void update(double now) {
    if (phase == Phase.DONE) {
      return;
    }
    BaseStatusSignal.refreshAll(loopSignals);
    double elapsed = now - phaseStart;

    if (phase != Phase.PREPARE && !motor.isConnected()) {
      abort("Lost CAN contact with the motor mid-run", true);
      return;
    }
    for (FaultSignal f : faults) {
      // Current-limit faults are expected while accelerating; they only count at steady state.
      boolean currentLimit = f.name().endsWith("CurrLimit");
      if (Boolean.TRUE.equals(f.live().getValue()) && (!currentLimit || phase == Phase.SAMPLE)) {
        faultsDuring.add(f.name());
      }
    }

    double vel = velocity.getValueAsDouble();
    double amps = Math.abs(stator.getValueAsDouble());
    if (phase == Phase.SETTLE || phase == Phase.SAMPLE) {
      if (amps > BINDING_A) {
        if (Double.isNaN(highCurrentSince)) {
          highCurrentSince = now;
        } else if (now - highCurrentSince > BINDING_S) {
          abort(
              String.format(
                  "Drew %.0f A with nothing on the shaft: binding, rubbing or a shorted winding",
                  amps),
              true);
          return;
        }
      } else {
        highCurrentSince = Double.NaN;
      }
    }

    switch (phase) {
      case PREPARE:
        if (elapsed >= PREPARE_S) {
          if (!motor.isConnected()) {
            abort("Motor is not answering on CAN", true);
            return;
          }
          BaseStatusSignal.refreshAll(connectedMotor, motorKv);
          for (FaultSignal f : faults) {
            f.sticky().refresh();
            if (Boolean.TRUE.equals(f.sticky().getValue())) {
              stickyBefore.add(f.name());
            }
          }
          motor.clearStickyFaults();
          ConnectedMotorValue type = connectedMotor.getValue();
          motorType = type == null ? "Unknown" : type.name();
          kvReported = motorKv.getValueAsDouble();
          tempStart = temp.getValueAsDouble();
          startStep(now);
        }
        break;
      case SETTLE:
        if (elapsed >= SETTLE_S) {
          double v = STEP_VOLTS[stepIndex];
          if (Math.abs(vel) < MIN_RPS_PER_VOLT * Math.abs(v)) {
            abort(
                String.format(
                    "Did not spin: %.1f rps at %+.0f V (is the shaft blocked or a phase lead off?)",
                    vel, v),
                true);
            return;
          }
          acc = new StepResult.Accumulator(v);
          enter(Phase.SAMPLE, now);
        }
        break;
      case SAMPLE:
        acc.add(
            now,
            vel,
            position.getValueAsDouble(),
            stator.getValueAsDouble(),
            supply.getValueAsDouble(),
            busVolts.getValueAsDouble(),
            pdhCurrents());
        if (elapsed >= SAMPLE_S) {
          steps.add(acc.result());
          if (stepIndex == COAST_AFTER_STEP) {
            coastStartVel = Math.abs(vel);
            motor.setControl(coastOut);
            enter(Phase.COAST, now);
          } else {
            nextStep(now);
          }
        }
        break;
      case COAST:
        double speed = Math.abs(vel);
        if (Double.isNaN(coastHalfS) && speed <= 0.5 * coastStartVel) {
          coastHalfS = elapsed;
        }
        if (speed <= 0.1 * coastStartVel || elapsed >= COAST_TIMEOUT_S) {
          coastFullS = elapsed;
          nextStep(now);
        }
        break;
      case STOPPING:
        if (Math.abs(vel) < 0.5 || elapsed >= STOP_TIMEOUT_S) {
          tempEnd = temp.getValueAsDouble();
          motor.setControl(neutralOut);
          phase = Phase.DONE;
        }
        break;
      default:
        break;
    }
  }

  /**
   * Stop now.
   *
   * @param byMotor true when the motor is the reason (the result is recorded); false when the
   *     operator stopped it (nothing is recorded)
   */
  void abort(String reason, boolean byMotor) {
    motor.setControl(neutralOut);
    if (phase == Phase.DONE) {
      return;
    }
    temp.refresh();
    tempEnd = temp.getValueAsDouble();
    abortReason = reason;
    operatorStopped = !byMotor;
    phase = Phase.DONE;
  }

  boolean isDone() {
    return phase == Phase.DONE;
  }

  /** True when the operator stopped it and there is nothing worth recording. */
  boolean operatorStopped() {
    return operatorStopped;
  }

  String progress() {
    switch (phase) {
      case PREPARE:
        return "preparing";
      case SETTLE:
      case SAMPLE:
        return String.format(
            "step %d/%d at %+.0f V", stepIndex + 1, STEP_VOLTS.length, STEP_VOLTS[stepIndex]);
      case COAST:
        return "coast-down";
      case STOPPING:
        return "stopping";
      default:
        return abortReason == null ? "done" : "stopped: " + abortReason;
    }
  }

  DeviceDiscovery.DeviceInfo info() {
    return info;
  }

  List<StepResult> steps() {
    return steps;
  }

  Set<String> stickyBefore() {
    return stickyBefore;
  }

  Set<String> faultsDuring() {
    return faultsDuring;
  }

  double tempStart() {
    return tempStart;
  }

  double tempEnd() {
    return tempEnd;
  }

  double coastHalfS() {
    return coastHalfS;
  }

  double coastFullS() {
    return coastFullS;
  }

  String motorType() {
    return motorType;
  }

  double kvReported() {
    return kvReported;
  }

  String abortReason() {
    return abortReason;
  }

  String configStatus() {
    return configStatus;
  }

  // ---------------------------------------------------------------- internals

  private void startStep(double now) {
    voltage.Output = STEP_VOLTS[stepIndex];
    motor.setControl(voltage);
    enter(Phase.SETTLE, now);
  }

  private void nextStep(double now) {
    stepIndex++;
    if (stepIndex >= STEP_VOLTS.length) {
      motor.setControl(coastOut);
      enter(Phase.STOPPING, now);
    } else {
      startStep(now);
    }
  }

  private void enter(Phase p, double now) {
    phase = p;
    phaseStart = now;
    highCurrentSince = Double.NaN;
  }

  private double[] pdhCurrents() {
    if (pdh == null) {
      return new double[0];
    }
    int n = pdh.getNumChannels();
    double[] c = new double[n];
    for (int i = 0; i < n; i++) {
      c[i] = pdh.getCurrent(i);
    }
    return c;
  }
}
