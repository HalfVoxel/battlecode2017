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
        }

        System.out.println("I'm an archon! ");
        // The code you want your robot to perform every round should be in this loop
        while (true) {
            double maxPoints = rc.getTeamVictoryPoints() + Math.floor(rc.getTeamBullets() / GameConstants.BULLET_EXCHANGE_RATE);
            if (maxPoints >= GameConstants.VICTORY_POINTS_TO_WIN || rc.getRoundNum() == rc.getRoundLimit() - 1) {
                double donate = Math.floor(rc.getTeamBullets() / GameConstants.BULLET_EXCHANGE_RATE) * GameConstants.BULLET_EXCHANGE_RATE;
                rc.donate((float) donate);
            }

            int gardenerCount = spawnedCount(RobotType.GARDENER);
            boolean saveForTank = false;
            int tankCount = spawnedCount(RobotType.TANK);
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

            if (rc.getTreeCount() > tankCount * 4 + 400 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1) {
                saveForTank = true;
            }

            if ((gardenerCount < 1 || rc.getTreeCount() > 3 * gardenerCount || rc.getTeamBullets() > RobotType.TANK.bulletCost + 100) && !saveForTank) {
                // Generate a random direction
                Direction dir = randomDirection();
                if (rc.canHireGardener(dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.hireGardener(dir);
                    rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1);
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
