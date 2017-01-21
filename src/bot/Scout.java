package bot;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;

class Scout extends Robot {

    private Map<Integer, Float> treeLifeMap = new HashMap<>();

    private boolean highPriorityTargetExists() throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        return rc.getRoundNum() < lastAttackingEnemySpotted + 20 || rc.getRoundNum() < lastGardenerSpotted + 20;
    }

    int treesWithBulletsIndex = 0;
    MapLocation lastFallbackTree = null;
    int lastFallbackIndex = -1;

    boolean checkTreeStillValidToShake (MapLocation loc, int index) throws GameActionException {
        if (rc.canSenseLocation(loc)) {
            TreeInfo tree = rc.senseTreeAtLocation(loc);
            if (tree == null || tree.containedBullets == 0) {
                removeTreeWithBullets(index);
                return false;
            }
        }

        return true;
    }

    private MapLocation pickTarget() throws GameActionException {
        MapLocation bestTarget = null;
        int bestIndex = -1;
        float bestScore = 0f;

        if (lastFallbackTree != null) {
            if (checkTreeStillValidToShake(lastFallbackTree, lastFallbackIndex)) {
                rc.setIndicatorLine(rc.getLocation(), lastFallbackTree, 0, 255, 0);

                float score = 1f / rc.getLocation().distanceTo(lastFallbackTree);
                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = lastFallbackTree;
                    bestIndex = lastFallbackIndex;
                }
            } else {
                rc.setIndicatorLine(rc.getLocation(), lastFallbackTree, 255, 0, 0);
                lastFallbackTree = null;
            }
        }

        // Check the global array of trees to shake
        int numTreesWithBullets = rc.readBroadcast(TREES_WITH_BULLETS);
        int cnt = Math.min(10, numTreesWithBullets);
        for (int k = 0; k < cnt; k++) {
            int index = (treesWithBulletsIndex + k) % numTreesWithBullets;
            int bullets = rc.readBroadcast(TREES_WITH_BULLETS + 1 + 3*index);
            MapLocation loc = readBroadcastPosition(TREES_WITH_BULLETS + 1 + 3*index + 1);

            if (!checkTreeStillValidToShake(loc, index)) {
                // This would have been decremented due to the tree being removed from the list
                numTreesWithBullets--;
                if (numTreesWithBullets == 0) break;

                rc.setIndicatorLine(rc.getLocation(), loc, 255, 0, 0);
            } else {
                rc.setIndicatorLine(rc.getLocation(), loc, 255, 255, 0);
                float score = 1f / rc.getLocation().distanceTo(loc);
                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = loc;
                    bestIndex = index;
                }
            }
        }

        treesWithBulletsIndex += cnt;

        if (bestTarget != null) {
            lastFallbackTree = bestTarget;
            lastFallbackIndex = bestIndex;
            return lastFallbackTree;
        } else {
            Direction dir = randomDirection();
            MapLocation target = rc.getLocation().add(dir, info.sensorRadius - 1f);
            if (!rc.onTheMap(target)) {
                dir = randomDirection();
                target = clampToMap(rc.getLocation().add(dir, info.sensorRadius - 1f));
            }
            return target;
        }
    }

    private float getPositionScore(MapLocation loc, MapLocation[] enemyArchons, RobotInfo[] units, int numBullets,
                                   float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                   float[] bulletDamage, float[] bulletSpeed, float[] bulletImpactDistances,
                                   TreeInfo bestTree, MapLocation target) throws GameActionException {
        Team myTeam = rc.getTeam();

        float score = 0f;
        score += 3f / (loc.distanceSquaredTo(target) + 10);

//        float disToEdge = getDistanceToMapEdge(loc);
//        if (disToEdge < 10)
//            score -= 0.05 * (disToEdge - 10) * (disToEdge - 10);

        for (RobotInfo unit : units) {
            if (unit.team == myTeam) {
                if (unit.type == RobotType.SCOUT)
                    score -= 1f / (loc.distanceSquaredTo(unit.location) + 1);
            } else {
                switch(unit.type) {
                    case GARDENER:
                        //score += 10f / (loc.distanceSquaredTo(unit.location) + 1);
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

        score -= 1000f * getEstimatedDamageAtPosition(loc.x, loc.y, numBullets, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, bulletImpactDistances);;

        return score;
    }

    private TreeInfo findBestTreeToShake(TreeInfo[] neutralTrees) {
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

        return bestTree;
    }

    private void detectTrees(TreeInfo[] neutralTrees) throws GameActionException {
        // Limit the number of trees we look at to the N closest
        int numTrees = Math.min(neutralTrees.length, 40);
        for (int i = 0; i < numTrees; i++) {
            TreeInfo tree = neutralTrees[i];
            if (tree.containedBullets > 0) {
                if (detect(tree.ID)) {
                    rc.setIndicatorDot(tree.location, 255, 255, 255);
                    detectTreeWithBullets(tree);
                }
            }
        }
    }

    private void fireAtNearbyTree(TreeInfo[] trees) throws GameActionException {
        Team enemy = rc.getTeam().opponent();
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        for (TreeInfo tree : trees) {
            if (Clock.getBytecodesLeft() < 2000)
                break;
            if (tree.getTeam() == enemy) {
                BodyInfo firstUnitHit = linecast(tree.location);
                if (firstUnitHit != null && firstUnitHit.isTree() && ((TreeInfo) firstUnitHit).getTeam() == enemy) {
                    TreeInfo t = (TreeInfo) firstUnitHit;
                    if (treeLifeMap.containsKey(t.getID()) && t.getHealth() < treeLifeMap.get(t.getID())) {
                        if (t.getHealth() > 30) {
                            if (rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME) {
                                rc.fireSingleShot(rc.getLocation().directionTo(tree.location));
                            }
                            break;
                        }
                    }
                    treeLifeMap.put(t.getID(), t.getHealth());
                }
            }
        }
    }

    float[] bulletX = new float[100];
    float[] bulletY = new float[100];
    float[] bulletDx = new float[100];
    float[] bulletDy = new float[100];
    float[] bulletDamage = new float[100];
    float[] bulletSpeed = new float[100];
    boolean[] isDangerous = new boolean[100];
    MapLocation[] movesToConsider = new MapLocation[100];

    public void run() throws GameActionException {
        System.out.println("I'm a scout!");

        Team enemy = rc.getTeam().opponent();
        MapLocation[] archons = rc.getInitialArchonLocations(enemy);
        MapLocation target = randomChoice(archons);

        // Sort of accurate check (will fail if multiple scouts are built at the same time)
        boolean isDefender = spawnedCount(RobotType.SCOUT) == 2;

        if (target == null) target = rc.getLocation();

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            shakeNearbyTrees();
            MapLocation myLocation = rc.getLocation();
            // See if there are any nearby enemy robots
            RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
            RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            RobotInfo[] allRobots = rc.senseNearbyRobots();
            BulletInfo[] nearbyBullets = rc.senseNearbyBullets(8f);
            float[] bulletImpactDistances = updateBulletHitDistances(nearbyBullets);
            TreeInfo[] neutralTrees = rc.senseNearbyTrees(-1, Team.NEUTRAL);


            int bulletsToConsider = Math.min(nearbyBullets.length, bulletX.length);
            for (int i = 0; i < bulletsToConsider; i++) {
                isDangerous[i] = bulletCanHitUs(myLocation, nearbyBullets[i]);
            }

            int numDangerous = 0;
            for (int i = 0; i < bulletsToConsider; i++) {
                if (!isDangerous[i])
                    continue;

                BulletInfo bullet = nearbyBullets[i];
                bulletX[numDangerous] = bullet.location.x;
                bulletY[numDangerous] = bullet.location.y;
                bulletDx[numDangerous] = bullet.dir.getDeltaX(1);
                bulletDy[numDangerous] = bullet.dir.getDeltaY(1);
                bulletDamage[numDangerous] = bullet.damage;
                bulletSpeed[numDangerous] = bullet.speed;
                numDangerous++;
            }

            // Pick a new target with a small probability or when very close to the target
            if (rnd.nextInt(200) < 1 || myLocation.distanceTo(target) < 4f) {
                target = pickTarget();
            }

            detectTrees(neutralTrees);
            TreeInfo bestTree = findBestTreeToShake(neutralTrees);

            RobotInfo defenderTarget = null;
            for (RobotInfo robot : friendlyRobots) {
                if (robot.getType() == RobotType.GARDENER) {
                    defenderTarget = robot;
                    break;
                }
            }

            if (isDefender && defenderTarget != null && robots.length == 0 && bulletsToConsider == 0) {
                float defDist = myLocation.distanceTo(defenderTarget.location);
                if (defDist < RobotType.GARDENER.bodyRadius + rc.getType().bodyRadius + 0.5) {
                    Direction tgDir = defenderTarget.location.directionTo(myLocation).rotateLeftRads(rc.getType().strideRadius / defDist);
                    MapLocation tg = defenderTarget.location.add(tgDir, defDist);
                    tryMove(tg);
                } else {
                    tryMove(defenderTarget.location);
                }
            } else {
                int numMovesToConsider = 0;
                RobotInfo closestEnemy = null;
                float disToClosestEnemy = 1000000f;

                if (myLocation.distanceTo(target) > 0) {
                    movesToConsider[numMovesToConsider++] = myLocation.add(myLocation.directionTo(target), info.strideRadius);
                }

                for (RobotInfo robot : robots) {
                    float dist = myLocation.distanceTo(robot.location);
                    if (dist < disToClosestEnemy) {
                        disToClosestEnemy = dist;
                        closestEnemy = robot;
                    }
                }

                if (closestEnemy != null) {
                    Direction dir = myLocation.directionTo(closestEnemy.location);
                    movesToConsider[numMovesToConsider++] = myLocation.add(dir.opposite(), info.strideRadius);
                }

                float bestScore = -1000000f;
                MapLocation bestMove = null;
                int iterationsDone = 0;
                int processingTime = 0;
                while (Clock.getBytecodesLeft()-processingTime > 3000 || iterationsDone < 2) {
                    MapLocation loc;
                    if (iterationsDone < numMovesToConsider) {
                        loc = movesToConsider[iterationsDone];
                    } else {
                        Direction dir = randomDirection();
                        int r = rnd.nextInt(10);
                        if (r < 5)
                            loc = myLocation.add(dir, info.strideRadius);
                        else if (r < 7)
                            loc = myLocation.add(dir, info.strideRadius * 0.5f);
                        else
                            loc = myLocation.add(dir, 0.2f);
                    }

                    if (rc.canMove(loc)) {
                        int bytecodesBefore = Clock.getBytecodesLeft();
                        float score = getPositionScore(loc, archons, allRobots,
                                numDangerous, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, bulletImpactDistances, bestTree, target);
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
                }
            }

            boolean targetArchons = !highPriorityTargetExists() && rc.getRoundNum() > 2000;
            fireAtNearbyRobot(friendlyRobots, robots, targetArchons);

            yieldAndDoBackgroundTasks();
        }
    }
}
