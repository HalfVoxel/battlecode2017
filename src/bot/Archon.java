package bot;

import battlecode.common.*;

class Archon extends Robot {

    @Override
    public void run() throws GameActionException {
        rc.broadcast(GARDENER_OFFSET, -1000);
        rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        int STOP_SPENDING_AT_TIME = 100;

        // Set the exploration origin if it has not been set already
        if (readBroadcastPosition(EXPLORATION_ORIGIN).equals(new MapLocation(0, 0))) {
            System.out.println("Set exploration origin");
            broadcast(EXPLORATION_ORIGIN, rc.getLocation());

            for (int i = 0; i < 4; i++) {
                rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + i + 1, mapEdges[i]);
            }
        }

        System.out.println("I'm an archon! ");

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int gardenerCount = spawnedCount(RobotType.GARDENER);
            boolean saveForTank = false;
            int tankCount = spawnedCount(RobotType.TANK);
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

            if (rc.getTreeCount() > tankCount * 4 + 400 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1) {
                saveForTank = true;
            }

            boolean gardenersSeemToBeBlocked = rc.readBroadcast(GARDENER_CAN_PROBABLY_BUILD) > gardenerCount * 20 + 10;
            if ((gardenersSeemToBeBlocked || gardenerCount < 1 || rc.getTreeCount() > 6 * gardenerCount || rc.getTeamBullets() > RobotType.TANK.bulletCost + 100) && !saveForTank) {
                // Generate a random direction
                Direction dir = randomDirection();
                if (rc.canHireGardener(dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.hireGardener(dir);
                    rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1);
                    if (gardenersSeemToBeBlocked) {
                        System.out.println("Hired gardener because all the existing ones seem to be blocked");
                    }

                    rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                }
            }

            BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
            RobotInfo[] units = rc.senseNearbyRobots();
            moveToAvoidBullets(rc.getLocation(), bullets, units);
            yieldAndDoBackgroundTasks();

            debug_resign();
        }
    }

    void debug_resign() {
        // Give up if the odds do not seem to be in our favor
        if (rc.getRoundNum() > 1800 && rc.getRobotCount() <= 2 && rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length >= 4 && rc.getTeamBullets() < 200 && rc.getTreeCount() <= 1) {
            System.out.println("RESIGNING");
            rc.resign();
        }
    }
}
