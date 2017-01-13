package bot;

import battlecode.common.*;

import java.util.Random;

class Tank extends Robot {

    Random rnd = new Random(1);

    MapLocation pickTarget (MapLocation[] fallBackPositions) {
        if (rnd.nextFloat() < 0.2) {
            return fallBackPositions[rnd.nextInt(fallBackPositions.length)];
        } else {
            Direction dir = randomDirection();
            return rc.getLocation().add(dir, info.strideRadius * 10);
        }
    }

    @Override
    public void run() throws GameActionException {
        System.out.println("I'm an tank!");

        Team enemy = rc.getTeam().opponent();
        MapLocation target = rc.getLocation();
        MapLocation[] archons = rc.getInitialArchonLocations(enemy);
        int stepsWithTarget = 0;
        int STOP_SPENDING_AT_TIME = 50;

        if(archons.length > 0) {
            Random rand = new Random(1);
            int ind = rand.nextInt(archons.length);
            target = archons[ind];
        }

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
            // See if there are any nearby enemy robots
            RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
            RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());

            if(robots.length > 0){
               MapLocation myLocation = rc.getLocation();
               MapLocation enemyLocation = robots[0].getLocation();
               Direction toEnemy = myLocation.directionTo(enemyLocation);
               tryMove(toEnemy);
            }

            if(!rc.hasMoved()) {
                try {
                    rc.setIndicatorDot(target, 255, 0, 0);
                } catch (Exception e) {
                }

                boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 10f;
                if (canSeeTarget) {
                    target = pickTarget(archons);
                    stepsWithTarget = 0;
                }

                if (!tryMove(target)) {
                    target = pickTarget(archons);
                    tryMove(target);
                }
            }

            // If there are some...
            if (robots.length > 0 && turnsLeft > STOP_SPENDING_AT_TIME) {
                if (rc.canFirePentadShot() && friendlyRobots.length < robots.length) {
                    // ...Then fire a bullet in the direction of the enemy.
                    rc.firePentadShot(rc.getLocation().directionTo(robots[0].location));
                }
                // And we have enough bullets, and haven't attacked yet this turn...
                if (rc.canFireSingleShot()) {
                    // ...Then fire a bullet in the direction of the enemy.
                    rc.fireSingleShot(rc.getLocation().directionTo(robots[0].location));
                }
            }

            yieldAndDoBackgroundTasks();
        }
    }
}
