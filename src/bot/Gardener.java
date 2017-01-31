package bot;

import battlecode.common.*;

class Gardener extends Robot {

    boolean blockedByNeutralTrees = false;
    int lastBuildLumberjackTime = -1000;

    void water() throws GameActionException {
        if (rc.canWater()) {
            TreeInfo[] trees = rc.senseNearbyTrees(type.bodyRadius + type.strideRadius + 0.01f, ally);

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

    float likelyValidTarget(MapLocation target, float freeRadius) throws GameActionException {
        if (!onMap(target, freeRadius)) {
            return 0;
        }

        float score = 1;

        if (rc.canSenseAllOfCircle(target, freeRadius)) {
            if (!rc.isCircleOccupiedExceptByThisRobot(target, freeRadius)) {
                score += 5;
            }

            if (!rc.isCircleOccupiedExceptByThisRobot(target, type.bodyRadius)) {
                score += 2f;
            }
        }

        score += Math.min(spawnPos.distanceTo(target)/(type.bodyRadius * 8), 1) * 3f;
        return score;
    }

    MapLocation pickTarget(float freeRadius) throws GameActionException {
        MapLocation target = null;
        float bestScore = -1000f;

        for (int i = 0; i < 10; i++) {
            // Pick a new target
            // Generate a random direction
            Direction dir = randomDirection();
            MapLocation tg = clampToMap(rc.getLocation().add(dir, type.strideRadius * 5 * rnd.nextFloat()), freeRadius);

            float score = likelyValidTarget(tg, freeRadius);

            /*if (rnd.nextFloat() < 0.5) {
                // Ensure it is far away from the spawn pos
                for (int i = 0; i < 4; i++) {
                    target = clampToMap(spawnPos.add(spawnPos.directionTo(target), Math.max(spawnPos.distanceTo(target), type.bodyRadius * 8)), freeRadius);
                }
            }*/

            if (score > bestScore) {
                bestScore = score;
                target = tg;
            }
        }

        return target;
    }

    boolean buildLumberjackInDenseForests() throws GameActionException {
        //if (spawnedCount(RobotType.LUMBERJACK) >= 2) return

        // Don't create lumberjacks too often (the previous one might not have had much time to chop down trees yet)
        if (rc.getRoundNum() < lastBuildLumberjackTime + 50) {
            return false;
        }

        TreeInfo[] trees = rc.senseNearbyTrees(type.sensorRadius, Team.NEUTRAL);
        float totalScore = 0f;
        boolean onlyGardenersAndArchons = true;
        int numGardenersAndArchons = 0;
        for (TreeInfo tree : trees) {
            // Add a small constant to make it favorable to just chop down trees for space
            totalScore += treeScore(tree, null) + 0.1f;
            if(tree.containedRobot != null && tree.containedRobot != RobotType.ARCHON && tree.containedRobot != RobotType.GARDENER){
                onlyGardenersAndArchons = false;
            }
            else if(tree.containedRobot != null){
                numGardenersAndArchons += 1;
            }
        }
        if(onlyGardenersAndArchons && spawnedCount(RobotType.LUMBERJACK) >= 2 && numGardenersAndArchons > 5){
            totalScore *= 0.7f;
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
            if (!rc.hasRobotBuildRequirements(RobotType.LUMBERJACK)) return true;
            for (int i = 0; i < 6; i++) {
                Direction dir = randomDirection();
                if (rc.canBuildRobot(RobotType.LUMBERJACK, dir)) {
                    buildRobot(RobotType.LUMBERJACK, dir);
                    lastBuildLumberjackTime = rc.getRoundNum();
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    // Store a single reserved location
    // Check it every frame. Store round number
    // Keep a score, change the reserved location if a better reserved location is detected

    MapLocation plantTrees(MapLocation settledLocation, boolean allowTreePlanting) throws GameActionException {
        blockedByNeutralTrees = false;

        if (allowTreePlanting) {
            for (int tries = 0; tries < 2; tries++) {
                for (int i = 0; i < 6; i++) {
                    Direction dir = new Direction(2 * (float)Math.PI * i / 6f);
                    MapLocation origPos = settledLocation != null ? settledLocation : rc.getLocation();
                    MapLocation plantPos = origPos.add(dir, type.bodyRadius + type.strideRadius + GameConstants.BULLET_TREE_RADIUS);

                    if (rc.isCircleOccupiedExceptByThisRobot(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f) || !onMap(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f)) {
                        TreeInfo tree = rc.senseTreeAtLocation(plantPos);
                        if (tries > 0 && ((tree != null && tree.team != ally) || (tree == null && rc.senseNearbyTrees(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f, Team.NEUTRAL).length > 0))) {
                            blockedByNeutralTrees = true;
                            rc.setIndicatorDot(plantPos, 255, 0, 255);
                        } else {
                            rc.setIndicatorDot(plantPos, 255, 0, 0);
                        }
                        continue;
                    } else {
                        rc.setIndicatorDot(plantPos, 0, 255, 0);
                    }

                    if (rc.canPlantTree(dir)) {
                        rc.plantTree(dir);
                        System.out.println("Planted tree");
                        settledLocation = origPos;
                        return settledLocation;
                    }
                }
            }
        }

        return settledLocation;
    }

    /**
     * Marks nearby trees as high priority for woodcutters, marks nearby nodes as reserved so that other units will try to avoid them
     */
    void reserveSettlingLocation(MapLocation loc) throws GameActionException {
        // Just settled
        // Mark all nearby trees as high priority for woodcutters to chop down
        TreeInfo[] trees = rc.senseNearbyTrees(loc, type.bodyRadius + 2 * GameConstants.BULLET_TREE_RADIUS + 0.01f, null);
        for (TreeInfo tree : trees) markAsHighPriority(tree.ID);

        int index = snapToNode(loc);
        reserveNode(index % PATHFINDING_WORLD_WIDTH, index / PATHFINDING_WORLD_WIDTH);
        for (int i = 0; i < 6; i++) {
            Direction dir = new Direction(2 * (float)Math.PI * i / 6f);
            int index2 = snapToNode(loc.add(dir, type.bodyRadius + GameConstants.BULLET_TREE_RADIUS));
            reserveNode(index2 % PATHFINDING_WORLD_WIDTH, index2 / PATHFINDING_WORLD_WIDTH);
        }
    }

    int bulletsInNearbyTrees() {
        int s = 0;
        for (TreeInfo tree : rc.senseNearbyTrees(-1, Team.NEUTRAL)) {
            if (tree.containedBullets > 0) {
                // +1 because it is is easier for a scout to get the bullets than some other
                // unit just randomly stumbling in to one.
                s += tree.containedBullets + 1;
            }
        }
        return s;
    }

    MapLocation target;
    final int STOP_SPENDING_AT_TIME = 100;
    final float desiredRadius = type.bodyRadius + 2.01f * GameConstants.BULLET_TREE_RADIUS;
    int moveFailCounter = 0;
    boolean hasBuiltScout = false;
    boolean hasSettled = false;
    int unsettledTime = 0;
    int movesWithTarget = 0;
    float speedToTarget = 0f;

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm a gardener!");

        target = rc.getLocation();

        buildLumberjackInDenseForests();
    }

    int[] buildOrderCountNoEnemies = new int[] { 1, 1, 1, 1, 2, 2, 2, 3, 4 };
    int[] buildOrderCountEnemies = new int[]   { 1, 1, 1, 2, 2, 2, 3, 3, 5 };

    @Override
    public void onUpdate() throws GameActionException {
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
        boolean saveForTank = false;
        int tankCount = spawnedCount(RobotType.TANK);
        int gardenerCount = spawnedCount(RobotType.GARDENER);
        int scoutCount = spawnedCount(RobotType.SCOUT);
        int soldierCount = spawnedCount(RobotType.SOLDIER);

        if (rc.getTreeCount() > tankCount * 20 + 10 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1 && scoutCount > 2) {
            saveForTank = true;
        }

        if (!hasSettled) {
            unsettledTime += 1;
            TreeInfo[] trees = rc.senseNearbyTrees(type.sensorRadius, ally);
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

        boolean saveForLumberjack = false;
        if (turnsLeft > STOP_SPENDING_AT_TIME)
            saveForLumberjack = buildLumberjackInDenseForests();

        boolean invalidTarget = (moveFailCounter > 5 || speedToTarget < type.strideRadius * 0.2f) && !hasSettled;
        boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 0.01f || rc.canSenseAllOfCircle(target, desiredRadius);

        RobotType buildTarget;
        if (scoutCount == 0 && (accurateSpawnedCount(RobotType.SOLDIER) >= 2 || bulletsInNearbyTrees() >= 50))
            buildTarget = RobotType.SCOUT;
        else
            buildTarget = RobotType.SOLDIER;
        int buildTargetCount = buildTarget == RobotType.SCOUT ? accurateSpawnedCount(RobotType.SCOUT) : accurateSpawnedCount(RobotType.SOLDIER);
        boolean saveForUnit;
        int[] buildOrder = enemiesHaveBeenSpotted ? buildOrderCountEnemies : buildOrderCountNoEnemies;
        double shouldHaveBuilt;
        if (rc.getTreeCount() < buildOrder.length) {
            shouldHaveBuilt = buildOrder[rc.getTreeCount()];
        } else {
            if (enemiesHaveBeenSpotted) {
                shouldHaveBuilt = Math.pow(rc.getTreeCount() + 1, 0.9) + 1;
            } else {
                shouldHaveBuilt = Math.pow(0.5f * rc.getTreeCount() + 1, 0.5);
            }
        }
        shouldHaveBuilt += Math.floor(rc.getTeamBullets() / 600);
        saveForUnit = !hasBuiltScout || shouldHaveBuilt > buildTargetCount;
        //saveForUnit = !hasBuiltScout || Math.pow(rc.getTreeCount() + 1, 0.9) > buildTargetCount;

        if (saveForUnit && !saveForTank && !saveForLumberjack && rc.isBuildReady() && rc.hasRobotBuildRequirements(buildTarget)) {
            saveForTank = true;
            boolean built = false;
            for (int i = 0; i < 6; i++) {
                Direction dir = new Direction(2 * (float)Math.PI * i / 6f);
                if (rc.canBuildRobot(buildTarget, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    buildRobot(buildTarget, dir);
                    rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                    hasBuiltScout = true;
                    built = true;
                    break;
                }
            }

            if (!built) {
                // Noes! Could not build ANYWHERE!
                rc.setIndicatorDot(rc.getLocation(), 255, 192, 203);
                rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, rc.readBroadcast(GARDENER_CAN_PROBABLY_BUILD) + 1);
                saveForUnit = false;
            }
        } else {
            rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
        }

        if (invalidTarget && movesWithTarget > 3) {
            target = pickTarget(desiredRadius);
            //System.out.println("Picked new target " + target)
            moveFailCounter = 0;
            movesWithTarget = 0;
            rc.setIndicatorDot(target, 255, 0, 0);
        }

        movesWithTarget++;
        rc.setIndicatorLine(rc.getLocation(), target, 255, 0, 0);

        if (rc.hasRobotBuildRequirements(RobotType.TANK) && saveForTank) {
            for (int i = 0; i < 6; i++) {
                Direction dir = new Direction(2 * (int)Math.PI * i / 6f);
                if (rc.canBuildRobot(RobotType.TANK, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.buildRobot(RobotType.TANK, dir);
                    tankCount += 1;
                    rc.broadcast(RobotType.TANK.ordinal(), tankCount);
                }
            }
        }

        if (canSeeTarget && ((!invalidTarget && rc.getLocation().distanceSquaredTo(target) < 2f) || unsettledTime > 30)) {
            // At target
            boolean allowPlantingTrees = !saveForTank && !saveForUnit && turnsLeft > STOP_SPENDING_AT_TIME && rc.hasTreeBuildRequirements() && rc.isBuildReady();
            MapLocation settledLocation = plantTrees(hasSettled ? target : null, allowPlantingTrees);
            if (settledLocation != null) {
                if (!hasSettled) {
                    // Just settled
                    reserveSettlingLocation(rc.getLocation());
                }
                target = settledLocation;
                hasSettled = true;
            }
        }

        if (!hasSettled) {
            BulletInfo[] bullets = rc.senseNearbyBullets(type.strideRadius + type.bodyRadius + 3f);
            RobotInfo[] units = rc.senseNearbyRobots();

            markEnemySpotted(units);

            if (!rc.hasMoved()) {
                float d1 = rc.getLocation().distanceTo(target);
                MapLocation moveTo = moveToAvoidBullets(target, bullets, units);
                if (moveTo != null)
                    rc.move(moveTo);
                float d2 = rc.getLocation().distanceTo(target);
                speedToTarget *= 0.5f;
                speedToTarget += 0.5f * (d1 - d2);
            }
        } else if (!enemiesHaveBeenSpotted) {
            markEnemySpotted(rc.senseNearbyRobots(type.sensorRadius, enemy));
        }

        /*
        // Randomly attempt to build a soldier or lumberjack in this direction
        if (rc.canBuildRobot(RobotType.SOLDIER, dir) && Math.random < .01) {
            buildRobot(RobotType.SOLDIER, dir)
        } else if (rc.canBuildRobot(RobotType.LUMBERJACK, dir) && Math.random < .01 && rc.isBuildReady) {
            buildRobot(RobotType.LUMBERJACK, dir)
        }
        // Move randomly
        tryMove(randomDirection)
        */
        // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
        water();
        yieldAndDoBackgroundTasks();
    }
}