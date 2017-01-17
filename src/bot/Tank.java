package bot;

import battlecode.common.*;

import java.util.Random;

class Tank extends Robot {

    MapLocation pickTarget(MapLocation[] fallBackPositions) throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < info.strideRadius * 8) {
            // Defend!
            return highPriorityTargetPos;
        }

        if (rnd.nextFloat() < 0.2) {
            return fallBackPositions[rnd.nextInt(fallBackPositions.length)];
        } else {
            Direction dir = randomDirection();
            return clampToMap(rc.getLocation().add(dir, info.strideRadius * 10), info.sensorRadius * 0.8f);
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

        // Exponentially decaying weighted average of the speed the unit has
        // toward the target. If this is very low (or negative) then the unit
        // is not making much progress and maybe a different target should be chosen
        float speedToTarget = 0f;

        if (archons.length > 0) {
            int ind = rnd.nextInt(archons.length);
            target = archons[ind];
        }

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
            // See if there are any nearby enemy robots
            RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
            RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);

            if (robots.length > 0) {
                MapLocation enemyLocation = robots[0].getLocation();
                moveToAvoidBullets(enemyLocation, bullets, robots);
            }

            if (!rc.hasMoved()) {
                rc.setIndicatorDot(target, 255, 0, 0);

                boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 10f;
                if (canSeeTarget) {
                    target = pickTarget(archons);
                    stepsWithTarget = 0;
                }

                float d1 = rc.getLocation().distanceTo(target);
                moveToAvoidBullets(target, bullets, robots);
                float d2 = rc.getLocation().distanceTo(target);
                speedToTarget *= 0.5f;
                speedToTarget += 0.5f * (d1 - d2);

                if (speedToTarget < info.strideRadius * 0.2f) {
                    target = pickTarget(archons);
                }
            }

            // If there are some...
            if (robots.length > 0 && turnsLeft > STOP_SPENDING_AT_TIME) {
                if (rc.canFirePentadShot() && rc.getTeamBullets() > 300 && friendlyRobots.length < robots.length && (friendlyRobots.length == 0 || robots.length >= 2)) {
                    // ...Then fire a bullet in the direction of the enemy.
                    rc.firePentadShot(rc.getLocation().directionTo(robots[0].location));
                }

                if (rc.canFireTriadShot() && friendlyRobots.length < robots.length && (friendlyRobots.length == 0 || robots.length >= 2)) {
                    // ...Then fire a bullet in the direction of the enemy.
                    rc.fireTriadShot(rc.getLocation().directionTo(robots[0].location));
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
