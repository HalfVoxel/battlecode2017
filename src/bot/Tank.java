package bot;

import battlecode.common.*;

class Tank extends Robot {

    MapLocation pickTarget(MapLocation[] fallBackPositions) throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < type.strideRadius * 20) {
            // Defend!
            return highPriorityTargetPos;
        }

        MapLocation bestTarget = getHighPriorityTarget();
        if (bestTarget != null) return bestTarget;

        // return pathfindingTarget();

        if (rnd.nextFloat() < 0.2) {
            return fallBackPositions[rnd.nextInt(fallBackPositions.length)];
        } else {
            Direction dir = randomDirection();
            return clampToMap(rc.getLocation().add(dir, type.strideRadius * 10), type.sensorRadius * 0.8f);
        }
    }

    MapLocation pathfindingTarget() throws GameActionException {
        MapLocation c = rc.getLocation();
        for (int i = 0; i < 5; i++) {
            MapLocation next = nextPointOnPathToEnemyArchon(c);
            rc.setIndicatorLine(c, next, 100, 100, 0);
            c = next;
        }

        // Try to take a shorter path
        if (linecast(c) == null) {
            return c;
        } else {
            return nextPointOnPathToEnemyArchon(rc.getLocation());
        }
    }

    MapLocation getHighPriorityTarget() throws GameActionException {
        MapLocation bestTarget = null;
        float bestPriority = 0.0f;

        for (int i = 0; i < NUMBER_OF_TARGETS; ++i) {
            int offset = TARGET_OFFSET + 10 * i;
            int timeSpotted = rc.readBroadcast(offset);
            MapLocation loc = readBroadcastPosition(offset + 2);
            float lastEventPriority = rc.readBroadcast(offset + 1) / (rc.getRoundNum() - timeSpotted + 5.0f);
            lastEventPriority /= loc.distanceSquaredTo(rc.getLocation()) + 10;
            //System.out.println("Target " + loc + " from frame " + timeSpotted + " has priority " + lastEventPriority);
            if (loc.distanceTo(rc.getLocation()) < 30f && lastEventPriority > bestPriority) {
                bestPriority = lastEventPriority;
                bestTarget = loc;
            }
        }
        if (bestTarget != null && bestPriority > 0.02) {
            //System.out.println("Heading for nearby target " + bestTarget);
            return bestTarget;
        }

        return null;
    }

    // Exponentially decaying weighted average of the speed the unit has
    // toward the target. If this is very low (or negative) then the unit
    // is not making much progress and maybe a different target should be chosen
    float speedToTarget = 0f;
    MapLocation target;
    int ticksMovingInTheWrongDirection = 0;

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm a tank!");
        target = rc.getLocation();

        target = randomChoice(initialArchonLocations, rc.getLocation());
    }

    @Override
    public void onUpdate() throws GameActionException {
        // See if there are any nearby enemy robots
        RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, ally);
        BulletInfo[] bullets = rc.senseNearbyBullets(type.strideRadius + type.bodyRadius + 3f);

        markEnemySpotted(robots);

        boolean targetArchons = rc.getTeamBullets() > 1000 || rc.getRoundNum() > 1000 || (rc.getRoundNum() > 600 && initialArchonLocations.length == 1);

        RobotInfo bestRobot = null;
        float bestRobotScore = 0f;
        for (RobotInfo robot : robots) {
            float score;
            switch (robot.getType()) {
                case ARCHON:
                    score = targetArchons ? 0.1f : 0f;
                    break;
                case GARDENER:
                    score = 1f;
                    break;
                case SCOUT:
                    score = 2f;
                    break;
                default:
                    score = 0.8f;
                    break;
            }

            if (score > bestRobotScore) {
                bestRobot = robot;
                bestRobotScore = score;
            }
        }

        if (bestRobot != null) {
            MapLocation moveTo = moveToAvoidBullets(bestRobot.location, bullets, robots);
            if (moveTo == null) {
                FirePlan firePlan = fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
                if (firePlan != null)
                    firePlan.apply(rc);
            } else {
                Direction preliminaryShootDirection = rc.getLocation().directionTo(bestRobot.location);
                Direction moveToDirection = rc.getLocation().directionTo(moveTo);
                if (moveToDirection == null)
                    moveToDirection = new Direction(0);
                float deg = Math.abs(preliminaryShootDirection.degreesBetween(moveToDirection));
                if (deg > 90) {
                    FirePlan firePlan = fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
                    if (firePlan == null) {
                        rc.move(moveTo);
                    } else if (Math.abs(firePlan.direction.degreesBetween(moveToDirection)) > 90) {
                        // We are moving away from the direction we are firing in, so it is best to shoot first and move after that
                        //System.out.println("Shoot first, move after");
                        firePlan.apply(rc);
                        rc.move(moveTo);
                    } else {
                        // We are moving in the direction we are firing in, so to avoid moving on top of own bullets
                        // we should move first and then shoot.
                        rc.move(moveTo);
                        firePlan = fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
                        if (firePlan != null) {
                            firePlan.apply(rc);
                        }
                    }
                } else {
                    rc.move(moveTo);
                    FirePlan firePlan = fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
                    if (firePlan != null) {
                        firePlan.apply(rc);
                    }
                }
            }
        }

        if (!rc.hasMoved()) {
            rc.setIndicatorDot(target, 255, 0, 0);

            boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 10f;
            if (canSeeTarget) {
                target = pickTarget(initialArchonLocations);
            }

            float d1 = rc.getLocation().distanceTo(target);
            MapLocation moveTo = moveToAvoidBullets(target, bullets, robots);
            if (moveTo != null)
                rc.move(moveTo);
            float d2 = rc.getLocation().distanceTo(target);
            speedToTarget *= 0.5f;
            speedToTarget += 0.5f * (d1 - d2);

            if (getHighPriorityTarget() != null) {
                target = pickTarget(initialArchonLocations);
            } else {
                if (speedToTarget < type.strideRadius * 0.2f) {
                    ticksMovingInTheWrongDirection++;
                    if (ticksMovingInTheWrongDirection > 40) {
                        target = pickTarget(initialArchonLocations);
                    }
                } else {
                    ticksMovingInTheWrongDirection = 0;
                }
            }
        }

        if (!rc.hasAttacked()) {
            fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
        }
        if (!rc.hasAttacked()) {
            int nearbyGardeners = 0;
            for (RobotInfo robot : robots) {
                if (robot.getType() == RobotType.GARDENER) {
                    nearbyGardeners += 1;
                }
            }
            TreeInfo[] enemyTrees = rc.senseNearbyTrees(-1, enemy);
            int nearbyTrees = enemyTrees.length;
            if (nearbyTrees / (nearbyGardeners + 0.01) > 15)
                fireAtNearbyTree(enemyTrees);
        }

        yieldAndDoBackgroundTasks();
    }
}
