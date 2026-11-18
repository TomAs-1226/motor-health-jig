package frc.robot;

import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

/**
 * The motor health jig: a roboRIO, a PDH and one or more Talon FX motors spinning free.
 *
 * <p>Type a label (the sticker on the motor) into {@code MotorHealth/Label} if you like, put the
 * Driver Station in <b>Test</b> mode and enable. Every Talon FX on the bus is tested in turn and a
 * record is written per motor. Disable at any time to stop. Teleop and Autonomous do nothing.
 */
public class Robot extends TimedRobot {
  private final DeviceDiscovery discovery = new DeviceDiscovery(JigConfig.DIAGNOSTIC_SERVER);
  private final RecordStore store = new RecordStore(JigConfig.recordDir());
  private final PowerDistribution pdh = JigConfig.openPdh();
  private JigSession session;

  public Robot() {
    SmartDashboard.setDefaultString(JigSession.NT + "Label", "");
    SmartDashboard.setDefaultString(JigSession.NT + "Notes", "");
    SmartDashboard.putString(JigSession.NT + "RecordDir", store.dir().toString());
    SmartDashboard.putString(
        JigSession.NT + "Status",
        "Ready. Clear the shaft, put the Driver Station in Test mode and enable.");
    discovery.start();
  }

  @Override
  public void robotPeriodic() {
    SmartDashboard.putString(JigSession.NT + "Discovery", discovery.status());
    SmartDashboard.putStringArray(
        JigSession.NT + "Devices",
        discovery.latest().stream().map(DeviceDiscovery.DeviceInfo::describe).toArray(String[]::new));
  }

  @Override
  public void testInit() {
    session = new JigSession(discovery, pdh, store);
    session.start(Timer.getFPGATimestamp());
  }

  @Override
  public void testPeriodic() {
    session.update(Timer.getFPGATimestamp());
  }

  @Override
  public void testExit() {
    stop();
  }

  @Override
  public void disabledInit() {
    stop();
  }

  @Override
  public void teleopInit() {
    SmartDashboard.putString(JigSession.NT + "Status", "This program only runs in Test mode.");
  }

  @Override
  public void autonomousInit() {
    teleopInit();
  }

  private void stop() {
    if (session != null) {
      session.stopByOperator();
      session = null;
    }
  }
}
