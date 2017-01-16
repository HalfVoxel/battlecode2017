package bot;

import battlecode.common.*;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

class Scout extends Robot {

    private Map<Integer, Float> treeLifeMap = new HashMap<>();
    private final int STOP_SPENDING_AT_TIME = 50;
    int lastAttackedEnemyID = -1;

    private boolean highPriorityTargetExists() throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        return rc.getRoundNum() < lastAttackingEnemySpotted + 20 || rc.getRoundNum() < lastGardenerSpotted + 20;
    }

    private MapLocation pickTarget() throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < info.strideRadius * 8) {
            // Defend!
            return highPriorityTargetPos;
        } else {
            int lastTimeGardenerSpotted = rc.readBroadcast(GARDENER_OFFSET);
            if (rc.getRoundNum() < lastTimeGardenerSpotted + 50) {
                // Follow that gardener!
                return readBroadcastPosition(GARDENER_OFFSET + 1);
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
    }

    private float getPositionScore(MapLocation loc, MapLocation[] enemyArchons,
                                   RobotInfo[] units, BulletInfo[] bullets, float[] bulletImpactDistances, TreeInfo bestTree, MapLocation target) throws GameActionException {
        Team myTeam = rc.getTeam();

        float score = 0f;
        score += 3f / (loc.distanceSquaredTo(target) + 10);

        for (RobotInfo unit : units) {
            if (unit.team == myTeam) {
                if (unit.getType() == RobotType.SCOUT)
                    score -= 1f / (loc.distanceSquaredTo(unit.location) + 1);
            } else {
                if (unit.getType() == RobotType.GARDENER) {
                    score += 10f / (loc.distanceSquaredTo(unit.location) + 1);
                } else if (unit.getType() == RobotType.SCOUT) {
                    if (rc.getHealth() >= unit.getHealth())
                        score += 2f / (loc.distanceSquaredTo(unit.location) + 1);
                    else
                        score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                } else if (unit.getType() == RobotType.LUMBERJACK) {
                    float dis = loc.distanceTo(unit.location);
                    score -= 10f / (dis * dis + 1);
                    score += 0.8f / (dis + 1);
                    if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f) {
                        score -= 1000;
                    }
                } else if (unit.getType() == RobotType.ARCHON) {
                    // Do nothing
                } else {
                    float dis = loc.distanceTo(unit.location);
                    score -= 5f / (dis * dis + 1);
                    score += 0.2f / (dis + 1);
                }
            }
        }

        if (bestTree != null) {
            score += (bestTree.containedBullets * 0.5) / (loc.distanceTo(bestTree.location) + 1);
        }

        score -= 1000f * getEstimatedDamageAtPosition(loc, bullets, bulletImpactDistances);
        return score;
    }



    private TreeInfo findBestTreeToShake(TreeInfo[] trees) {
        TreeInfo bestTree = null;
        float bestTreeScore = -1000000f;
        for (TreeInfo tree : trees) {
            if (tree.getTeam() == Team.NEUTRAL) {
                float score = tree.containedBullets / (1 + rc.getLocation().distanceTo(tree.location));
                if (score > bestTreeScore) {
                    bestTree = tree;
                    bestTreeScore = score;
                }
            }
        }

        return bestTree;
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

    /**
     * Fire at any nearby robots if possible.
     *
     * @param robots
     * @return If there are any nearby gardeners (maybe move to different method?)
     * @throws GameActionException
     */
    private boolean fireAtNearbyRobot(RobotInfo[] robots) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        boolean nearbyEnemyGardener = false;
        List<Integer> bestRobotsTried = new ArrayList<>();
        for (int attemptN = 0; attemptN < 4; ++attemptN) {
            RobotInfo bestRobot = null;
            float bestScore2 = 0;
            MapLocation closestThreat = null;

            for (RobotInfo robot : robots) {
                if (bestRobotsTried.contains(robot.ID))
                    continue;
                float score = 0;
                if (robot.getType() == RobotType.GARDENER) {
                    score += 100;
                    nearbyEnemyGardener = true;
                } else if (robot.getType() == RobotType.ARCHON)
                    score += (highPriorityTargetExists() || rc.getRoundNum() < 2000 ? 0f : 1f);
                else if (robot.getType() == RobotType.SCOUT)
                    score += 150;
                if (robot.getType() == RobotType.LUMBERJACK || robot.getType() == RobotType.SOLDIER || robot.getType() == RobotType.TANK) {
                    if (closestThreat == null || robot.location.distanceTo(myLocation) < closestThreat.distanceTo(myLocation)) {
                        closestThreat = robot.location;
                    }
                    score += 50;
                }
                score /= myLocation.distanceTo(robot.getLocation()) + 1;
                if (score > bestScore2) {
                    bestScore2 = score;
                    bestRobot = robot;
                }
            }

            if (bestRobot != null) {
                lastAttackedEnemyID = bestRobot.getID();
                bestRobotsTried.add(bestRobot.ID);

                BodyInfo firstUnitHit = linecast(bestRobot.location);
                if (rc.canFireSingleShot() && rc.getLocation().distanceTo(bestRobot.location) < 2 * info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam().opponent() && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.fireSingleShot(rc.getLocation().directionTo(bestRobot.location));
                    break;
                }
            } else {
                break;
            }
            if(Clock.getBytecodesLeft() < 3000)
                break;
        }

        return nearbyEnemyGardener;
    }

    public void run() throws GameActionException {
        System.out.println("I'm a scout!");

        Team enemy = rc.getTeam().opponent();
        MapLocation[] archons = rc.getInitialArchonLocations(enemy);
        Random rand = new Random(1);
        MapLocation target = randomChoice(archons);

        if (target == null) target = rc.getLocation();

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int roundAtStart = rc.getRoundNum();
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
            MapLocation myLocation = rc.getLocation();
            // See if there are any nearby enemy robots
            RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
            RobotInfo[] allRobots = rc.senseNearbyRobots();
            BulletInfo[] nearbyBullets = rc.senseNearbyBullets(8f);
            float[] bulletImpactDistances = updateBulletHitDistances(nearbyBullets);
            TreeInfo[] trees = rc.senseNearbyTrees();

            // Pick a new target with a small probability or when very close to the target
            if (rand.nextInt(200) < 1 || myLocation.distanceTo(target) < 4f) {
                target = pickTarget();
            }

            int clock1 = Clock.getBytecodeNum();

            TreeInfo bestTree = findBestTreeToShake(trees);

            List<MapLocation> movesToConsider = new ArrayList<>();
            RobotInfo closestEnemy = null;
            float disToClosestEnemy = 1000000f;

            if (myLocation.distanceTo(target) > 0) {
                movesToConsider.add(myLocation.add(myLocation.directionTo(target), info.strideRadius));
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
                movesToConsider.add(myLocation.add(dir.opposite(), info.strideRadius));
                movesToConsider.add(myLocation.add(dir,
                        Math.max(0f, Math.min(myLocation.distanceTo(closestEnemy.location) - 2.01f, info.strideRadius))));
            }

            float bestScore = -1000000f;
            MapLocation bestMove = null;
            int iterationsDone = 0;
            while (Clock.getBytecodesLeft() > 5000 || iterationsDone < 2) {
                iterationsDone += 1;
                MapLocation loc;
                if (movesToConsider.isEmpty()) {
                    Direction dir = randomDirection();
                    int r = rand.nextInt(10);
                    if (r < 5)
                        loc = myLocation.add(dir, info.strideRadius);
                    else if (r < 7)
                        loc = myLocation.add(dir, info.strideRadius*0.5f);
                    else
                        loc = myLocation.add(dir, 0.2f);
                } else {
                    loc = movesToConsider.get(0);
                    movesToConsider.remove(0);
                }

                if (rc.canMove(loc)) {
                    float score = getPositionScore(loc, archons, allRobots, nearbyBullets, bulletImpactDistances, bestTree, target);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMove = loc;
                    }
                }
            }
            //System.out.println("Completed " + iterationsDone + " iterations");
            if (bestMove != null) {
                rc.move(bestMove);
            }

            boolean nearbyEnemyGardener = false;

            // If there are some enemy robots around
            if (robots.length > 0) {
                nearbyEnemyGardener = fireAtNearbyRobot(robots);
            }

            if (!rc.hasAttacked() && rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME) {
                float bestScore3 = -1000000f;
                RobotInfo bestRobot = null;
                for (RobotInfo robot : robots) {
                    if (robot.getType() != RobotType.SCOUT)
                        continue;
                    if (myLocation.distanceTo(robot.location) > 5.5f)
                        continue;
                    float score = 1;
                    score /= myLocation.distanceTo(robot.getLocation()) + 1;
                    if (score > bestScore3) {
                        bestScore3 = score;
                        bestRobot = robot;
                    }
                }

                if (bestRobot != null) {
                    lastAttackedEnemyID = bestRobot.getID();
                    BodyInfo firstUnitHit = linecast(bestRobot.location);
                    if (firstUnitHit.isTree()) {
                        TreeInfo tree = (TreeInfo)firstUnitHit;

                        if ((tree.health < 10 || tree.health > 25)) {
                            rc.fireSingleShot(rc.getLocation().directionTo(bestRobot.location));
                            System.out.println("Firing despite trees!");
                        }
                    }
                }
            }

            if (!nearbyEnemyGardener && !rc.hasAttacked()) {
                fireAtNearbyTree(trees);
            }

            if(rc.getRoundNum() != roundAtStart) {
                System.out.println("Error! Did not finish within the bytecode limit");
            }

            yieldAndDoBackgroundTasks();
        }
    }
}
