package bot;

import battlecode.common.*;

class Tank extends Robot {

    static MapLocation pickTarget(MapLocation[] fallBackPositions) throws GameActionException {
        return pickTarget(fallBackPositions, 0.2f);
    }

    static MapLocation pickTarget(MapLocation[] fallBackPositions, float pickFallbackProbability) throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50) {
            MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
            int limit = isDefender ? 40 : 20;
            if (rc.getLocation().distanceTo(highPriorityTargetPos) < type.strideRadius * limit) {
                // Defend!
                return highPriorityTargetPos;
            }
        }

        MapLocation bestTarget = getHighPriorityTarget();
        if (bestTarget != null) {
            return bestTarget;
        }

        // return pathfindingTarget();

        if (rnd.nextFloat() < pickFallbackProbability) {
            if (isDefender) {
                return pickRandomSpreadOutTarget(ourInitialArchonLocations, 1);
            } else {
                return pickRandomSpreadOutTarget(fallBackPositions == null ? readArchonLocations() : fallBackPositions, 0);
            }
        } else {
            Direction dir = randomDirection();
            return clampToMap(rc.getLocation().add(dir, type.strideRadius * 10), type.sensorRadius * 0.8f);
        }
    }

    static MapLocation pathfindingTarget() throws GameActionException {
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

    static MapLocation highPriorityTargetCache;
    static int highPriorityTargetCacheTime;

    static MapLocation getHighPriorityTarget() throws GameActionException {
        if (rc.getRoundNum() == highPriorityTargetCacheTime) return highPriorityTargetCache;

        MapLocation bestTarget = null;
        float bestPriority = 0.0f;
        MapLocation myLocation = rc.getLocation();

        for (int i = 0; i < NUMBER_OF_TARGETS; ++i) {
            int offset = TARGET_OFFSET + 10 * i;
            MapLocation loc = readBroadcastPosition(offset + 2);
            if (loc.distanceTo(myLocation) < 30f) {
                int timeSpotted = rc.readBroadcast(offset);
                float lastEventPriority = rc.readBroadcastFloat(offset + 1) / (rc.getRoundNum() - timeSpotted + 5.0f);
                lastEventPriority /= loc.distanceSquaredTo(myLocation) + 10;
                //System.out.println("Target " + loc + " from frame " + timeSpotted + " has priority " + lastEventPriority);
                if (lastEventPriority > bestPriority) {
                    bestPriority = lastEventPriority;
                    bestTarget = loc;
                }
            }
        }

        highPriorityTargetCacheTime = rc.getRoundNum();

        if (bestTarget != null && bestPriority > 0.02) {
            highPriorityTargetCache = bestTarget;
            return bestTarget;
        }

        highPriorityTargetCache = null;
        return null;
    }

    // Exponentially decaying weighted average of the speed the unit has
    // toward the target. If this is very low (or negative) then the unit
    // is not making much progress and maybe a different target should be chosen
    static float speedToTarget = 0f;
    static MapLocation target;
    static int ticksMovingInTheWrongDirection = 0;
    static boolean isDefender;

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm a tank!");
        target = rc.getLocation();

        if (type == RobotType.SOLDIER && spawnedCount(type) == 2) {
            isDefender = true;
        }

        target = pickTarget(null, 1f);
    }

    static void fireAtNearbyNeutralTree(TreeInfo[] trees) throws GameActionException {
        if (rc.getTeamBullets() < 100 || rc.getRoundLimit() - rc.getRoundNum() <= STOP_SPENDING_AT_TIME || trees.length == 0) return;

        rc.firePentadShot(rc.getLocation().directionTo(trees[0].location));
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
            switch (robot.type) {
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
                target = pickTarget(null);
            }

            float d1 = rc.getLocation().distanceTo(target);
            MapLocation moveTo = moveToAvoidBullets(target, bullets, robots);
            if (moveTo != null)
                rc.move(moveTo);
            float d2 = rc.getLocation().distanceTo(target);
            speedToTarget *= 0.5f;
            speedToTarget += 0.5f * (d1 - d2);

            if (isStuck()) {
                fireAtNearbyNeutralTree(rc.senseNearbyTrees(type.bodyRadius + 1, Team.NEUTRAL));
            }

            if (getHighPriorityTarget() != null) {
                target = pickTarget(null);
            } else {
                if (speedToTarget < type.strideRadius * 0.2f) {
                    ticksMovingInTheWrongDirection++;
                    if (ticksMovingInTheWrongDirection > 40) {
                        target = pickTarget(null);
                    }
                } else {
                    ticksMovingInTheWrongDirection = 0;
                }
            }
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
