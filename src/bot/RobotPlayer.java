package bot;

import battlecode.common.*;

@SuppressWarnings("unused")
public strictfp class RobotPlayer {

    @SuppressWarnings("unused")
    public static void run(RobotController rc) {
        try {
            Robot.init(rc);
            Robot robot = null;
            switch (rc.getType()) {
                case ARCHON:
                    robot = new Archon();
                    break;
                case GARDENER:
                    robot = new Gardener();
                    break;
                case SOLDIER:
                    robot = new Soldier();
                    break;
                case LUMBERJACK:
                    robot = new Lumberjack();
                    break;
                case SCOUT:
                    robot = new Scout();
                    break;
                case TANK:
                    robot = new Tank();
                    break;
            }

            robot.onAwake();
            robot.onStartOfTick();
            while(true) {
                robot.onUpdate();
            }
        } catch (Exception e) {
            System.out.println("Exception in " + rc.getType());
            e.printStackTrace();
        }
    }
}
