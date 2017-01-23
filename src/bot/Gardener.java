package bot;

import battlecode.common.*;

import java.util.ArrayList;

class Gardener extends Robot {

    boolean blockedByNeutralTrees = false;
    int lastBuildLumberjackTime = -1000;

    void water(TreeInfo[] trees) throws GameActionException {
        if (rc.canWater()) {
            if (trees == null) {
                trees = rc.senseNearbyTrees(info.bodyRadius + info.strideRadius + 0.01f, rc.getTeam());
            }

            TreeInfo minHealthTree = null;
            for (TreeInfo tree : trees) {
                if ((minHealthTree == null || tree.health < minHealthTree.health) && rc.canWater(tree.getID())) {
                    minHealthTree = tree;
                }
            }

            if (minHealthTree != null) {
                rc.water(minHealthTree.getID());
            }
        }
    }

    boolean likelyValidTarget(MapLocation target, float freeRadius) throws GameActionException {
        if (spawnPos.isWithinDistance(target, info.bodyRadius * 8)) {
            return false;
        }

        boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 0.01f || rc.canSenseAllOfCircle(target.add(0, 0.001f), freeRadius);

        if (canSeeTarget && (!onMap(target.add(0, 0.001f), freeRadius) || rc.isCircleOccupiedExceptByThisRobot(target.add(0, 0.001f), freeRadius))) {
            return false;
        }

        return true;
    }

    MapLocation pickTarget(float freeRadius) throws GameActionException {
        MapLocation target = null;
        int tests = 0;
        do {
            // Pick a new target
            // Generate a random direction
            Direction dir = randomDirection();
            target = clampToMap(rc.getLocation().add(dir, info.strideRadius * 5), freeRadius);

            if (rnd.nextFloat() < 0.5) {
                // Ensure it is far away from the spawn pos
                for (int i = 0; i < 4; i++) {
                    target = clampToMap(spawnPos.add(spawnPos.directionTo(target), Math.max(spawnPos.distanceTo(target), info.bodyRadius * 8)), freeRadius);
                }
            }

            tests += 1;
        } while (tests < 10 && !likelyValidTarget(target, freeRadius));

        return target;
    }

    void buildLumberjackInDenseForests() throws GameActionException {
        if (!rc.hasRobotBuildRequirements(RobotType.LUMBERJACK)) return;
        //if (spawnedCount(RobotType.LUMBERJACK) >= 2) return

        // Don't create lumberjacks too often (the previous one might not have had much time to chop down trees yet)
        if (rc.getRoundNum() < lastBuildLumberjackTime + 50) {
            return;
        }

        TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius, Team.NEUTRAL);
        float totalScore = 0f;
        for (TreeInfo tree : trees) {
            // Add a small constant to make it favorable to just chop down trees for space
            totalScore += treeScore(tree, null) + 0.1f;
        }

        if (blockedByNeutralTrees) {
            totalScore += 1f;
        }

        // Very approximate
        float turnsToBreakEven = RobotType.LUMBERJACK.bulletCost / (totalScore + 0.001f);

        //System.out.println("Score " + totalScore + " turns to break even " + turnsToBreakEven)
        float modifier = (1 + rc.getTeamBullets() * 0.001f) / (1f + spawnedCount(RobotType.LUMBERJACK));
        boolean createLumberjack = false;
        if (spawnedCount(RobotType.LUMBERJACK) == 0 && rc.getTeamBullets() > 200 && rc.getTreeCount() > 0)
            createLumberjack = true;
        if (createLumberjack || turnsToBreakEven < 100 * modifier) {
            // Create a woodcutter
            for (int i = 0; i < 6; i++) {
                Direction dir = randomDirection();
                if (rc.canBuildRobot(RobotType.LUMBERJACK, dir)) {
                    rc.buildRobot(RobotType.LUMBERJACK, dir);
                    rc.broadcast(RobotType.LUMBERJACK.ordinal(), spawnedCount(RobotType.LUMBERJACK) + 1);
                    lastBuildLumberjackTime = rc.getRoundNum();
                    return;
                }
            }
        }
    }

    void tryBuildUnits (Direction fixedDir) throws GameActionException {
        boolean saveForTank = false;
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
        int scoutCount = spawnedCount(RobotType.SCOUT);
        int soldierCount = spawnedCount(RobotType.SOLDIER);
        int tankCount = spawnedCount(RobotType.TANK);
        int gardenerCount = spawnedCount(RobotType.GARDENER);

        if (rc.getTreeCount() > tankCount * 20 + 10 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1 && scoutCount > 2) {
            saveForTank = true;
        }

        RobotType buildTarget;
        if (scoutCount == 0 || (scoutCount == 1 && rc.getTreeCount() >= 3) || (scoutCount == 2 && rc.getTreeCount() >= 6))
            buildTarget = RobotType.SCOUT;

        else
            buildTarget = RobotType.SOLDIER;
        int buildTargetCount = buildTarget == RobotType.SCOUT ? scoutCount : soldierCount;
        if ((!hasBuiltScout || Math.pow(rc.getTreeCount() + 1, 0.9) > buildTargetCount) && !saveForTank && rc.isBuildReady() && rc.hasRobotBuildRequirements(buildTarget)) {
            saveForTank = true;
            boolean built = false;
            if (fixedDir != null) {
                if (rc.canBuildRobot(buildTarget, fixedDir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.buildRobot(buildTarget, fixedDir);
                    rc.broadcast(buildTarget.ordinal(), buildTargetCount + 1);
                    rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                    hasBuiltScout = true;
                    built = true;
                }
            } else {
                // Try a bunch of directions
                for (int i = 0; i < 9; i++) {
                    Direction dir = new Direction(2 * (float)Math.PI * i / 9f);
                    if (rc.canBuildRobot(buildTarget, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                        rc.buildRobot(buildTarget, dir);
                        rc.broadcast(buildTarget.ordinal(), buildTargetCount + 1);
                        rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                        hasBuiltScout = true;
                        built = true;
                        break;
                    }
                }
            }

            if (!built) {
                // Noes! Could not build ANYWHERE!
                rc.setIndicatorDot(rc.getLocation(), 255, 192, 203);
                rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, rc.readBroadcast(GARDENER_CAN_PROBABLY_BUILD) + 1);
            }
        } else {
            rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
        }

        if (rc.hasRobotBuildRequirements(RobotType.TANK) && saveForTank) {
            for (int i = 0; i < 6; i++) {
                Direction dir = new Direction(2 * (int) Math.PI * i / 6f);
                if (rc.canBuildRobot(RobotType.TANK, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.buildRobot(RobotType.TANK, dir);
                    tankCount += 1;
                    rc.broadcast(RobotType.TANK.ordinal(), tankCount);
                }
            }
        }
    }

    int moveDir = 1;

    boolean reserved = false;

    int plantationMinX = 0;
    int plantationMaxX = 0;

    MapLocation plantTrees(MapLocation settledLocation) throws GameActionException {
        // return plantTreesOld(settledLocation);

        int nodeIndex = snapToNode(rc.getLocation());
        int x = nodeIndex % PATHFINDING_WORLD_WIDTH;
        int y = nodeIndex / PATHFINDING_WORLD_WIDTH;

        if ((y == bestPlantationY + 1 && x >= bestPlantationX && x < bestPlantationX + 4) || reserved) {
            // At target
            if (!reserved) {
                // Reserve nodes
                reserved = true;

                plantationMinX = x;
                plantationMaxX = x;
                reserveNode(x, bestPlantationY);
                reserveNode(x, bestPlantationY + 1);
                reserveNode(x, bestPlantationY + 2);
            }

            // Try to plant trees above and below
            MapLocation snapped = nodePosition(x, y);
            if (Math.abs(rc.getLocation().y - snapped.y) > 0.01f) {
                if (rc.canMove(snapped) && !rc.hasMoved()) {
                    System.out.println("Moved to the correct y coordinate");
                    rc.move(snapped);
                } else {
                    System.out.println("Could not move to the correct y coordinate");
                    return settledLocation;
                }
            }

            rc.setIndicatorDot(snapped, 200, 100, 100);

            if (rc.hasTreeBuildRequirements() && rc.isBuildReady() && !rc.hasMoved() && !rc.isCircleOccupiedExceptByThisRobot(snapped, info.bodyRadius)) {
                MapLocation up = nodePosition(x, y + 1);
                MapLocation down = nodePosition(x, y - 1);

                if (!rc.isCircleOccupiedExceptByThisRobot(up, GameConstants.BULLET_TREE_RADIUS) || !rc.isCircleOccupiedExceptByThisRobot(down, GameConstants.BULLET_TREE_RADIUS)) {
                    // Move to the snapped node position
                    System.out.println("Trying to move to plant trees");

                    for (int i = 0; i < 3 && !rc.getLocation().isWithinDistance(snapped, 0.0001f); i++) {
                        if (i > 0) yieldAndDoBackgroundTasks();

                        if (rc.canMove(snapped)) {
                            rc.move(snapped);
                        } else {
                            System.out.println("Move failed " + i);
                        }
                    }

                    if (rc.getLocation().isWithinDistance(snapped, 0.001f)) {

                        if (rc.canPlantTree(rc.getLocation().directionTo(up))) {
                            rc.plantTree(rc.getLocation().directionTo(up));
                        }

                        if (rc.canPlantTree(rc.getLocation().directionTo(down))) {
                            rc.plantTree(rc.getLocation().directionTo(down));
                        }
                    } else {
                        System.out.println("Not close enough");
                    }
                }
            }

            MapLocation targetLoc = nodePosition(Math.min(Math.max(x + moveDir, plantationMinX), plantationMaxX), y);
            rc.setIndicatorLine(nodePosition(plantationMinX, y).add((float)Math.PI*0.5f, 0.2f), nodePosition(plantationMaxX, y).add((float)Math.PI*0.5f, 0.2f), 255, 255, 200);
            rc.setIndicatorLine(rc.getLocation(), targetLoc, 255, 255, 255);

            // If we were blocked by a gardener:
            //    clamp the plantation range
            //    turn around
            // elif we are at the end of the plantation range
            //    try to expand the plantation in that direction
            //    turn around
            // elif we were blocked by something:
            //    clamp the plantation range to only the next tile
            //    turn around
            boolean blocked = !rc.canMove(targetLoc);
            RobotInfo blockingRobot = rc.senseRobotAtLocation(targetLoc);
            if (blocked && blockingRobot != null && blockingRobot.type == RobotType.GARDENER) {
                if (moveDir == 1) plantationMaxX = x;
                else plantationMinX = x;

                moveDir *= -1;
                System.out.println("Flipping direction because blocked (1)");
            } else if (rc.getLocation().isWithinDistance(targetLoc, 0.1f)) {
                // Check if we can expand the plantation in that direction

                boolean didExpand = false;

                MapLocation up = nodePosition(x + moveDir, y + 1);
                MapLocation down = nodePosition(x + moveDir, y - 1);
                TreeInfo treeUp = rc.senseTreeAtLocation(up);
                TreeInfo treeDown = rc.senseTreeAtLocation(up);

                // Mark nearby trees as high priority (for woodcutters)
                for (TreeInfo tree : rc.senseNearbyTrees(targetLoc, 2, null)) {
                    markAsHighPriority(tree.ID);
                }

                if (treeUp == null || treeDown == null) {
                    tryBuildUnits(new Direction(moveDir == 1 ? 0 : (float)Math.PI));
                }

                if (plantationMaxX - plantationMinX < 4 && onMap(targetLoc, PATHFINDING_NODE_SIZE * 0.5f)) {
                    didExpand = true;
                    // TODO: Mark trees as high priority

                    //if (treeUp == null || treeDown == treeUp.team == rc.getTeam() || treeDown)

                    int reserveX;
                    if (moveDir == 1) {
                        reserveX = plantationMaxX = x + 1;
                        System.out.println("Expanded plantation to +X");
                    } else {
                        reserveX = plantationMinX = x - 1;
                        System.out.println("Expanded plantation to -X");
                    }

                    reserveNode(reserveX, bestPlantationY);
                    reserveNode(reserveX, bestPlantationY + 1);
                    reserveNode(reserveX, bestPlantationY + 2);
                }

                if (!didExpand || blocked) {
                    moveDir *= -1;
                    System.out.println("Flipping direction because blocked|didExpand (2)");
                }
            } else if (blocked) {
                if (moveDir == 1) plantationMaxX = Math.min(plantationMaxX, x + 1);
                else plantationMinX = Math.max(plantationMinX, x - 1);

                // Mark nearby trees as high priority (for woodcutters)
                for (TreeInfo tree : rc.senseNearbyTrees(rc.getLocation(), 2, null)) {
                    markAsHighPriority(tree.ID);
                }
                moveDir *= -1;
                System.out.println("Flipping direction because blocked (3)");
            }

            if (rc.canMove(targetLoc) && !rc.hasMoved()) {
                rc.move(targetLoc);
            }

            return rc.getLocation();
        } else {
            return settledLocation;
        }
    }

    MapLocation plantTreesOld(MapLocation settledLocation) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        blockedByNeutralTrees = false;
        boolean tryAgain = true;

        for (int tries = 0; tries < 2 && tryAgain; tries++) {
            tryAgain = false;

            for (int i = 0; i < 6; i++) {
                if (rc.hasMoved()) break;

                Direction dir = new Direction(2 * (float) Math.PI * i / 6f);
                MapLocation origPos = settledLocation != null ? settledLocation : rc.getLocation();
                MapLocation plantPos = origPos.add(dir, info.bodyRadius + info.strideRadius + GameConstants.BULLET_TREE_RADIUS);
                if (rc.isCircleOccupiedExceptByThisRobot(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f) || !onMap(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f)) {
                    TreeInfo tree = rc.senseTreeAtLocation(plantPos);
                    if (tries > 0 && ((tree != null && tree.team != rc.getTeam()) || (tree == null && rc.senseNearbyTrees(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f, Team.NEUTRAL).length > 0))) {
                        blockedByNeutralTrees = true;
                        rc.setIndicatorDot(plantPos, 255, 0, 255);
                    } else {
                        rc.setIndicatorDot(plantPos, 255, 0, 0);
                    }
                    continue;
                } else {
                    rc.setIndicatorDot(plantPos, 0, 255, 0);
                }


                MapLocation moveToPos = origPos.add(dir, info.strideRadius - 0.02f);

                /*if (rc.canMove(moveToPos)) {
                    rc.move(moveToPos);
                } else if (tries == 0) {
                    tryAgain = true;
                    continue;
                }*/

                if (rc.canPlantTree(dir)) {
                    rc.plantTree(dir);
                    System.out.println("Planted tree");
                    settledLocation = origPos;
                }

                /*yieldAndDoBackgroundTasks();
                for (int t = 0; t < 5 && !rc.canMove(origPos); t++) yieldAndDoBackgroundTasks();

                // Move back
                if (rc.canMove(origPos)) {
                    rc.move(origPos);
                }*/
            }
        }

        return rc.getLocation();
    }

    static int bestPlantationX = -1;
    static int bestPlantationY = -1;
    static float bestPlantationScore = -1000;
    static final int SEARCH_WIDTH = 7;
    static float[][] outerScore = new float[SEARCH_WIDTH][SEARCH_WIDTH];
    static float[][] innerScore = new float[SEARCH_WIDTH][SEARCH_WIDTH];
    static boolean[][] blacklist = new boolean[SEARCH_WIDTH][SEARCH_WIDTH];
    static ArrayList<Integer> blacklistX = new ArrayList<>();
    static ArrayList<Integer> blacklistY = new ArrayList<>();

    static int plantationSearchX;
    static int plantationSearchY;
    static int plantationPhase = 1;

    void searchForPlantationLocation() throws GameActionException {
        if (rc.getRoundNum() % 100 == 0) {
            blacklistX.clear();
            blacklistY.clear();
        }

        if (plantationPhase == 1) {
            plantationPhase = 2;
            searchForPlantationLocationPhase1();
        } else {
            plantationPhase = 1;
            searchForPlantationLocationPhase2();
        }
    }

    void searchForPlantationLocationPhase1 () throws GameActionException {
        int nindex = snapToNode(rc.getLocation());
        plantationSearchX = nindex % PATHFINDING_WORLD_WIDTH - SEARCH_WIDTH / 2;
        plantationSearchY = nindex / PATHFINDING_WORLD_WIDTH - SEARCH_WIDTH / 2;

        bestPlantationScore = -1000;


//            rc.setIndicatorLine(nodePosition(x, y), nodePosition(x + 3, y), 0, 0, 0);
//            rc.setIndicatorLine(nodePosition(x + 3, y), nodePosition(x + 3, y + 2), 0, 0, 0);
//            rc.setIndicatorLine(nodePosition(x + 3, y + 2), nodePosition(x, y + 2), 0, 0, 0);
//            rc.setIndicatorLine(nodePosition(x, y + 2), nodePosition(x, y), 0, 0, 0);


        for (int dy = 0; dy < SEARCH_WIDTH; dy++) {
            float[] inner = innerScore[dy];
            float[] outer = outerScore[dy];
            for (int dx = 0; dx < SEARCH_WIDTH; dx++) {
                int x = plantationSearchX + dx;
                int y = plantationSearchY + dy;
                int node = nodeInfo(x, y);
                boolean blocked = (node & 1) != 0;
                boolean fullyExplored = (node & 2) != 0;

                blacklist[dy][dx] = false;

                if (blocked) {
                    inner[dx] = -5;
                    outer[dx] = -1;

                    MapLocation loc = nodePosition(x, y);
                    if (rc.canSenseAllOfCircle(loc, PATHFINDING_NODE_SIZE * 0.5f)) {
                        TreeInfo tree = rc.senseTreeAtLocation(loc);
                        if (tree != null && tree.team == rc.getTeam()) {
                            outer[dx] += 2f;
                            inner[dx] -= 5f;
                        }
                    }
                } else if (fullyExplored) {
                    outer[dx] = inner[dx] = 1f;
                } else {
                    // Might be blocked or it might not be
                    outer[dx] = inner[dx] = 0f;
                }
            }
        }

        for (int i = 0; i < blacklistX.size(); i++) {
            int dx = blacklistX.get(i) - plantationSearchX;
            int dy = blacklistY.get(i) - plantationSearchY;
            if (dx >= 0 && dx < SEARCH_WIDTH && dy >= 0 && dy < SEARCH_WIDTH) {
                blacklist[dy][dx] = true;
            }
        }
    }

    void searchForPlantationLocationPhase2 () throws GameActionException {
        MapLocation[] archons = rc.getInitialArchonLocations(rc.getTeam().opponent());
        MapLocation[] ourArchons = rc.getInitialArchonLocations(rc.getTeam());
        for (int dy = 0; dy < SEARCH_WIDTH - 2; dy++) {
            float[] s0 = outerScore[dy];
            float[] s1 = outerScore[dy + 1];
            float[] s2 = outerScore[dy + 2];

            float score;
            float vp = 0;
            float v0 = s0[0] + s1[0] + s2[0];
            float v1 = s0[1] + s1[1] + s2[1];
            float v2 = s0[2] + s1[2] + s2[2];

            // Calculate score sum over a moving window (w*h = 4*3 nodes)
            score = v0 + v1 + v2;
            for (int dx = 0; dx < SEARCH_WIDTH - 3; dx++) {
                if (blacklist[dy][dx]) continue;

                // Try to have this as the top left corner of a plantation
                int x = plantationSearchX + dx;
                int y = plantationSearchY + dy;

                float v3 = s0[dx + 3] + s1[dx + 3] + s2[dx + 3];
                score = score + v3 - vp;

                vp = v0;
                v0 = v1;
                v1 = v2;
                v2 = v3;

                MapLocation p0 = nodePosition(x + 1, y + 1);
                for (MapLocation archon : archons) {
                    float dist = p0.distanceTo(archon);
                    if (dist < PATHFINDING_NODE_SIZE * 3) {
                        score -= 20;
                    }
                    score -= 30f / (1 + 0.2f * dist);
                }

                for (MapLocation archon : ourArchons) {
                    float dist = p0.distanceTo(archon);
                    if (dist < PATHFINDING_NODE_SIZE * 3) {
                        score -= 10;
                    }
                    score -= 5f / (1 + 0.2f * dist);
                }

                // Check if the whole region is on the map
                if (!onMapX(p0.x - PATHFINDING_NODE_SIZE * 1.5f, 0) || !onMapX(p0.x + PATHFINDING_NODE_SIZE * 2.5f, 0) || !onMapY(p0.y, PATHFINDING_NODE_SIZE * 1.5f)) {
                    score -= 100;
                }

                //System.out.println("Final score: " + score + " Best: " + bestPlantationScore);

                if (score > bestPlantationScore) {
                    bestPlantationScore = score;
                    bestPlantationX = x;
                    bestPlantationY = y;
                }
            }
        }

        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                rc.setIndicatorDot(nodePosition(bestPlantationX + dx, bestPlantationY + dy), 0, 255, 150);
            }
        }
    }

    boolean hasBuiltScout = false;
    static final int STOP_SPENDING_AT_TIME = 100;

    @Override
    public void run() throws GameActionException {
        System.out.println("I'm a gardener!");

        // The code you want your robot to perform every round should be in this loop

        MapLocation target = rc.getLocation();
        float desiredRadius = info.bodyRadius + 2.01f * GameConstants.BULLET_TREE_RADIUS;
        int moveFailCounter = 0;
        boolean hasSettled = false;
        int unsettledTime = 0;
        int movesWithTarget = 0;
        float speedToTarget = 0f;

        buildLumberjackInDenseForests();

        while (true) {
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

            if (!hasSettled) {
                searchForPlantationLocation();

                unsettledTime += 1;
                if (bestPlantationX != -1) {
                    int x = snapToNode(rc.getLocation()) % PATHFINDING_WORLD_WIDTH;
                    x = Math.max(Math.min(x, bestPlantationX + 3), bestPlantationX);
                    target = nodePosition(x, bestPlantationY + 1);
                } else {
                    TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius, rc.getTeam());
                    TreeInfo minHealthTree = null;
                    float bestScore = -1000000;
                    for (TreeInfo tree : trees) {
                        float score = (50f - tree.health) / tree.location.distanceTo(rc.getLocation());
                        if (minHealthTree == null || score > bestScore) {
                            // This probably means the tree isn't tended to by anyone else
                            minHealthTree = tree;
                            bestScore = score;
                        }
                    }
                    if (minHealthTree != null) {
                        tryMove(rc.getLocation().directionTo(minHealthTree.location));
                        target = rc.getLocation();
                    }
                }
            }

            // TODO: & no enemies nearby
            if ((moveFailCounter > 5 || speedToTarget < info.strideRadius * 0.2f) && bestPlantationX != -1) {
                blacklistX.add(bestPlantationX);
                blacklistY.add(bestPlantationY);
            }

            boolean invalidTarget = true; // (moveFailCounter > 5 || speedToTarget < info.strideRadius * 0.2f || !likelyValidTarget(target, desiredRadius)) && !hasSettled;
            boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 0.01f || rc.canSenseAllOfCircle(target, desiredRadius);

            if (!hasSettled) {
                tryBuildUnits(null);
            }

            /*if (invalidTarget && movesWithTarget > 3) {
                target = pickTarget(desiredRadius);
                //System.out.println("Picked new target " + target)
                moveFailCounter = 0;
                movesWithTarget = 0;
                rc.setIndicatorDot(target, 255, 0, 0);
            }*/

            movesWithTarget++;
            rc.setIndicatorLine(rc.getLocation(), target, 255, 0, 0);

            if (turnsLeft > STOP_SPENDING_AT_TIME)
                buildLumberjackInDenseForests();



            MapLocation settledLocation = plantTrees(hasSettled ? target : null);
            if (settledLocation != null) {
                target = settledLocation;
                hasSettled = true;
            }

            /*if (canSeeTarget && ((!invalidTarget && rc.getLocation().distanceSquaredTo(target) < 2f) || unsettledTime > 30) && !saveForTank && turnsLeft > STOP_SPENDING_AT_TIME && rc.hasTreeBuildRequirements() && rc.isBuildReady()) {
                // At target
                MapLocation settledLocation = plantTrees(hasSettled ? target : null);
                if (settledLocation != null) {
                    target = settledLocation;
                    hasSettled = true;
                }

                //System.out.println("Lost all trees around me, moving again")
            }*/

            if (!hasSettled) {
                BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
                RobotInfo[] units = rc.senseNearbyRobots();

                if (!rc.hasMoved()) {
                    float d1 = rc.getLocation().distanceTo(target);
                    moveToAvoidBullets(target, bullets, units);
                    float d2 = rc.getLocation().distanceTo(target);
                    speedToTarget *= 0.5f;
                    speedToTarget += 0.5f * (d1 - d2);
                }
                /*if (!rc.hasMoved() && moveToAvoidBullets(target, )) {
                    moveFailCounter = 0;
                } else {
                    // Couldn't move there? huh
                    moveFailCounter += 1;
                    //System.out.println("Move failed")
                }*/
            }

			/*
            // Randomly attempt to build a soldier or lumberjack in this direction
			if (rc.canBuildRobot(RobotType.SOLDIER, dir) && Math.random < .01) {
				rc.buildRobot(RobotType.SOLDIER, dir)
			} else if (rc.canBuildRobot(RobotType.LUMBERJACK, dir) && Math.random < .01 && rc.isBuildReady) {
				rc.buildRobot(RobotType.LUMBERJACK, dir)
			}
			// Move randomly
			tryMove(randomDirection)
			*/
            // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
            water(null);
            yieldAndDoBackgroundTasks();
        }
    }
}
