package bot;

import battlecode.common.*;

class Scout extends Robot {

    private boolean highPriorityTargetExists() throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        return rc.getRoundNum() < lastAttackingEnemySpotted + 20 || rc.getRoundNum() < lastGardenerSpotted + 20;
    }

    private MapLocation pickTarget() throws GameActionException {
        Direction dir = randomDirection();
        MapLocation target = rc.getLocation().add(dir, type.sensorRadius - 1f);
        if (!rc.onTheMap(target)) {
            dir = randomDirection();
            target = clampToMap(rc.getLocation().add(dir, type.sensorRadius - 1f));
        }
        return target;
    }

    private float getPositionScore(MapLocation loc, RobotInfo[] units, int numBullets,
                                   float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                   float[] bulletDamage, float[] bulletSpeed, float[] bulletImpactDistances,
                                   TreeInfo bestTree, MapLocation target) {
        float score = 0f;
        score += 3f / (loc.distanceSquaredTo(target) + 10);

        for (RobotInfo unit : units) {
            if (unit.team == ally) {
                if (unit.type == RobotType.SCOUT)
                    score -= 1f / (loc.distanceSquaredTo(unit.location) + 1);
            } else {
                switch (unit.type) {
                    case GARDENER:
                        score += 10f / (loc.distanceSquaredTo(unit.location) + 1);
                        break;
                    case SCOUT:
                        if (rc.getHealth() >= unit.health)
                            score += 0.1f / (loc.distanceSquaredTo(unit.location) + 1);
                        else
                            score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                        break;
                    case LUMBERJACK:
                        float dis = loc.distanceTo(unit.location);
                        score -= 24f / (dis * dis + 1);
                        score += 2f / (dis + 1);
                        if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f) {
                            score -= 1000;
                        }
                        break;
                    case ARCHON:
                        break;
                    default:
                        float dis2 = loc.distanceTo(unit.location);
                        score -= 12f / (dis2 * dis2 + 1);
                        score += 1f / (dis2 + 1);
                        break;
                }
            }
        }

        if (bestTree != null) {
            score += (bestTree.containedBullets * 0.5) / (loc.distanceTo(bestTree.location) + 1);
        }

        score -= 1000f * getEstimatedDamageAtPosition(loc.x, loc.y, numBullets, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, bulletImpactDistances);

        return score;
    }


    int lastBestTree = -1;
    int lastBestTreeTick = -1;

    private TreeInfo findBestTreeToShake() throws GameActionException {
        // Cache the best tree for a few ticks
        if (rc.getRoundNum() < lastBestTreeTick + 10 && rc.canSenseTree(lastBestTree)) {
            TreeInfo tree = rc.senseTree(lastBestTree);
            if (tree.containedBullets > 0) {
                return tree;
            }
        }

        TreeInfo[] neutralTrees = rc.senseNearbyTrees(-1, Team.NEUTRAL);
        TreeInfo bestTree = null;
        float bestTreeScore = -1000000f;
        MapLocation myLocation = rc.getLocation();

        // Limit the number of trees we look at to the N closest
        int numTrees = Math.min(neutralTrees.length, 40);
        for (int i = 0; i < numTrees; i++) {
            TreeInfo tree = neutralTrees[i];
            if (tree.containedBullets > 0) {
                float score = tree.containedBullets / (1 + myLocation.distanceTo(tree.location));
                if (score > bestTreeScore) {
                    bestTree = tree;
                    bestTreeScore = score;
                }
            }
        }

        if (bestTree != null) {
            lastBestTree = bestTree.ID;
            lastBestTreeTick = rc.getRoundNum();
        }

        return bestTree;
    }

    MapLocation target;

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm a scout!");
        target = randomChoice(initialArchonLocations, rc.getLocation());
    }

    @Override
    public void onUpdate() throws GameActionException {
        shakeNearbyTrees();

        while(rc.getRobotCount() == 1) {
            TreeInfo atTree = rc.senseTreeAtLocation(rc.getLocation());
            if (atTree != null && atTree.radius > type.bodyRadius && rc.senseNearbyRobots(type.bodyRadius + type.bulletSpeed, enemy).length == 0) {
                rc.move(atTree.location);
                yieldAndDoBackgroundTasks();
            } else {
                break;
            }
        }

        MapLocation myLocation = rc.getLocation();
        // See if there are any nearby enemy robots
        RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, ally);
        RobotInfo[] allRobots = rc.senseNearbyRobots();
        BulletInfo[] nearbyBullets = rc.senseNearbyBullets(8f);

        int bulletsToConsider = Math.min(nearbyBullets.length, bulletX.length);
        // Discard bullets that cannot hit us and move the bullets that can hit us to the front of the array
        // The first bulletsToConsider bullets will can potentially hit us after the loop is done
        for (int i = 0; i < bulletsToConsider; i++) {
            if (!bulletCanHitUs(myLocation, nearbyBullets[i])) {
                bulletsToConsider--;
                nearbyBullets[i] = nearbyBullets[bulletsToConsider];
                i--;
            } else {
                BulletInfo bullet = nearbyBullets[i];
                bulletX[i] = bullet.location.x;
                bulletY[i] = bullet.location.y;
                bulletDx[i] = bullet.dir.getDeltaX(1);
                bulletDy[i] = bullet.dir.getDeltaY(1);
                bulletDamage[i] = bullet.damage;
                bulletSpeed[i] = bullet.speed;
            }
        }

        float[] bulletImpactDistances = updateBulletHitDistances(nearbyBullets, bulletsToConsider);

        // Pick a new target with a small probability or when very close to the target
        if (rnd.nextInt(200) < 1 || myLocation.distanceTo(target) < 4f) {
            target = pickTarget();
        }

        TreeInfo bestTree = findBestTreeToShake();

        int numMovesToConsider = 0;

        if (myLocation.distanceTo(target) > 0) {
            movesToConsider[numMovesToConsider++] = myLocation.add(myLocation.directionTo(target), type.strideRadius);
        }

        if (robots.length > 0) {
            Direction dir = myLocation.directionTo(robots[0].location);
            movesToConsider[numMovesToConsider++] = myLocation.add(dir.opposite(), type.strideRadius);
        }

        if (bestTree != null) {
            Direction dir = myLocation.directionTo(bestTree.location);
            movesToConsider[numMovesToConsider++] = myLocation.add(dir, type.strideRadius);
        }

        if (previousBestMove != null) {
            movesToConsider[numMovesToConsider++] = previousBestMove;
        }

        float bestScore = -1000000f;
        MapLocation bestMove = null;
        int iterationsDone = 0;
        int processingTime = 0;

        // Save some processing power if we have no bullets to consider (will be used by e.g the exploration code)
        int maxIterations = bulletsToConsider == 0 ? 6 : 1000;
        while ((Clock.getBytecodesLeft() - processingTime > 3000 && iterationsDone < maxIterations) || iterationsDone < 3) {
            MapLocation loc;
            if (iterationsDone < numMovesToConsider) {
                loc = movesToConsider[iterationsDone];
            } else {
                Direction dir = randomDirection();
                int r = rnd.nextInt(10);
                if (r < 8)
                    loc = myLocation.add(dir, type.strideRadius);
                else if (r < 9)
                    loc = myLocation.add(dir, type.strideRadius * 0.5f);
                else
                    loc = myLocation.add(dir, 0.2f);
            }

            if (rc.canMove(loc)) {
                int bytecodesBefore = Clock.getBytecodesLeft();
                float score = getPositionScore(loc, allRobots,
                        bulletsToConsider, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, bulletImpactDistances, bestTree, target);
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = loc;
                }
                processingTime = bytecodesBefore - Clock.getBytecodesLeft();
            }

            iterationsDone += 1;
        }

        if (bestMove != null) {
            rc.move(bestMove);
            previousBestMove = bestMove;
        }

        boolean targetArchons = !highPriorityTargetExists() && rc.getRoundNum() > 2000;
        FirePlan firePlan = fireAtNearbyRobot(friendlyRobots, robots, targetArchons);
        if (firePlan != null) {
            firePlan.apply(rc);
        }

        yieldAndDoBackgroundTasks();
    }
}
