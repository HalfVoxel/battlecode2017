package bot;

import battlecode.common.*;

public strictfp class RobotPlayer {
    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * If this method returns, the robot dies!
     **/
    public static void run(RobotController rc) throws GameActionException {
        // Here, we've separated the controls into a different method for each RobotType.
        try {
            Robot robot = null;
            if (rc.getType() == RobotType.ARCHON) robot = new Archon();
            if (rc.getType() == RobotType.GARDENER) robot = new Gardener();
            if (rc.getType() == RobotType.SOLDIER) robot = new Soldier();
            if (rc.getType() == RobotType.LUMBERJACK) robot = new Lumberjack();
            if (rc.getType() == RobotType.SCOUT) robot = new Scout();
            if (rc.getType() == RobotType.TANK) robot = new Tank();

            robot.rc = rc;
            robot.init();
            robot.run();
        } catch (Exception e) {
            System.out.println("Exception in " + rc.getType());
            e.printStackTrace();
        }
    }
}
