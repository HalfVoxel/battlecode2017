package bot;

import battlecode.common.*;

public strictfp class RobotPlayer {
    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * If this method returns, the robot dies!
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) {
        // Here, we've separated the controls into a different method for each RobotType.
        try {
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

            Robot.rc = rc;
            robot.init();
            robot.run();
        } catch (Exception e) {
            System.out.println("Exception in " + rc.getType());
            e.printStackTrace();
        }
    }
}
