package bot;

import battlecode.common.*;

import java.nio.channels.AcceptPendingException;
import java.util.Random;

class Gardener extends Robot {

    void water() throws GameActionException {
        if (rc.canWater()) {
            TreeInfo[] trees = rc.senseNearbyTrees(info.bodyRadius + info.strideRadius + 0.01f, rc.getTeam());
            TreeInfo minHealthTree = null;
            for (TreeInfo tree : trees) {
                if (minHealthTree == null || tree.health < minHealthTree.health && rc.canWater(tree.getID())) {
                    minHealthTree = tree;
                }
            }

            if (minHealthTree != null) {
                rc.water(minHealthTree.getID());
            }
        }

        shakeNearbyTrees();
    }

    boolean likelyValidTarget (MapLocation target, float freeRadius) throws GameActionException {
        if (spawnPos.isWithinDistance(target, info.bodyRadius*8)) {
            return false;
        }

        boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 0.01f || rc.canSenseAllOfCircle(target, freeRadius);

        if (canSeeTarget && (!rc.onTheMap(target.add(0, 0.001f), info.bodyRadius + GameConstants.BULLET_TREE_RADIUS) || rc.isCircleOccupiedExceptByThisRobot(target.add(0, 0.001f), freeRadius))) {
            return false;
        }

        return true;
    }

    MapLocation pickTarget (float freeRadius) throws GameActionException {
        MapLocation target = null;
        int tests = 0;
        do {
            // Pick a new target
            // Generate a random direction
            Direction dir = randomDirection();
            target = rc.getLocation().add(dir, info.strideRadius * 3);

            if (Math.random() < 0.5) {
                // Ensure it is far away from the spawn pos
                target = spawnPos.add(spawnPos.directionTo(target), Math.max(spawnPos.distanceTo(target), info.bodyRadius * 8));
            }

            tests += 1;
        } while (tests < 10 && !likelyValidTarget(target, freeRadius));

        return target;
    }

    void buildLumberjackInDenseForests() throws GameActionException {
        if (!rc.hasRobotBuildRequirements(RobotType.LUMBERJACK)) return;
        //if (spawnedCount(RobotType.LUMBERJACK) >= 2) return

        TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius, Team.NEUTRAL);
        float totalScore = 0f;
        for (TreeInfo tree : trees) {
            // Add a small constant to make it favorable to just chop down trees for space
            totalScore += treeScore(tree, null) + 0.1f;
        }

        // Very approximate
        float turnsToBreakEven = RobotType.LUMBERJACK.bulletCost / (totalScore + 0.001f);

        //System.out.println("Score " + totalScore + " turns to break even " + turnsToBreakEven)
        float modifier = (1 + rc.getTeamBullets()*0.001f) / (1f + spawnedCount(RobotType.LUMBERJACK));
        boolean createLumberjack = false;
        if(spawnedCount(RobotType.LUMBERJACK) == 0 && rc.getTeamBullets() > 200 && rc.getTreeCount() > 0)
            createLumberjack = true;
        if (createLumberjack || turnsToBreakEven < 100 * modifier) {
            // Create a woodcutter
            for (int i = 0; i < 6; i++) {
                Direction dir = randomDirection();
                if (rc.canBuildRobot(RobotType.LUMBERJACK, dir)) {
                    rc.buildRobot(RobotType.LUMBERJACK, dir);
                    rc.broadcast(RobotType.LUMBERJACK.ordinal(), spawnedCount(RobotType.LUMBERJACK) + 1);
                    return;
                }
            }
        }
    }

    MapLocation plantTrees(MapLocation settledLocation) throws GameActionException {
        boolean tryAgain = true;
        for (int tries = 0; tries < 2 && tryAgain; tries++) {
            tryAgain = false;

            for (int i = 0; i < 9; i++) {
                if (rc.hasMoved()) break;

                Direction dir = new Direction(2 * (float) Math.PI * i / 9f);
                MapLocation origPos = settledLocation != null ? settledLocation : rc.getLocation();
                MapLocation plantPos = origPos.add(dir, info.bodyRadius + info.strideRadius + GameConstants.BULLET_TREE_RADIUS);
                if (rc.isCircleOccupiedExceptByThisRobot(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f) && rc.onTheMap(plantPos, GameConstants.BULLET_TREE_RADIUS + 0.01f)) {
                    rc.setIndicatorDot(plantPos, 255, 0, 0);
                    continue;
                } else {
                    rc.setIndicatorDot(plantPos, 0, 255, 0);
                }

                MapLocation moveToPos = origPos.add(dir, info.strideRadius - 0.02f);

                if (rc.canMove(moveToPos)) {
                    rc.move(moveToPos);
                } else if (tries == 0) {
                    tryAgain = true;
                    continue;
                }

                if (rc.canPlantTree(dir)) {
                    rc.plantTree(dir);
                    System.out.println("Planted tree");
                    settledLocation = origPos;
                }

                yieldAndDoBackgroundTasks();
                for (int t = 0; t < 5 && !rc.canMove(origPos); t++) yieldAndDoBackgroundTasks();

                // Move back
                if (rc.canMove(origPos)) {
                    rc.move(origPos);
                }
            }
        }

        return settledLocation;
    }

    @Override
    public void run() throws GameActionException {
        System.out.println("I'm a gardener!");

        // The code you want your robot to perform every round should be in this loop

        MapLocation target = rc.getLocation();
        float desiredRadius = info.bodyRadius + 2.01f*GameConstants.BULLET_TREE_RADIUS;
        int moveFailCounter = 0;
        boolean hasBuiltScout = false;
        boolean hasSettled = false;
        int unsettledTime = 0;
        int STOP_SPENDING_AT_TIME = 100;

        buildLumberjackInDenseForests();

        while (true) {
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
            boolean saveForTank = false;
            int tankCount = spawnedCount(RobotType.TANK);
            int gardenerCount = spawnedCount(RobotType.GARDENER);
            int scoutCount = spawnedCount(RobotType.SCOUT);

            if(rc.getTreeCount() > tankCount*20+10 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1 && scoutCount > 2){
                saveForTank = true;
            }

            if(!hasSettled) {
                unsettledTime += 1;
                TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius, rc.getTeam());
                TreeInfo minHealthTree = null;
                float bestScore = -1000000;
                for (TreeInfo tree : trees) {
                    float score = (50f-tree.health)/tree.location.distanceTo(rc.getLocation());
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

            boolean invalidTarget = (moveFailCounter > 5 || !likelyValidTarget(target, desiredRadius)) && !hasSettled;
            boolean canSeeTarget = target.distanceSquaredTo(rc.getLocation()) < 0.01f || rc.canSenseAllOfCircle(target, desiredRadius);

            if ((!hasBuiltScout || Math.pow(rc.getTreeCount()+2, 0.9) > scoutCount) && !saveForTank){
                saveForTank = true;
                for (int i = 0; i < 6; i++) {
                    Direction dir = new Direction(2 * (float)Math.PI * i / 6f);
                    if (rc.canBuildRobot(RobotType.SCOUT, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                        rc.buildRobot(RobotType.SCOUT, dir);
                        rc.broadcast(RobotType.SCOUT.ordinal(), scoutCount + 1);
                        hasBuiltScout = true;
                    }
                }
            }

            if (invalidTarget) {
                target = pickTarget(desiredRadius);
                //System.out.println("Picked new target " + target)
                moveFailCounter = 0;
                try {
                    rc.setIndicatorDot(target, 255, 0, 0);
                } catch (Exception e) {
                }
            }

            if(turnsLeft > STOP_SPENDING_AT_TIME)
                buildLumberjackInDenseForests();

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

            if (canSeeTarget && ((!invalidTarget && rc.getLocation().distanceSquaredTo(target) < 2f) || unsettledTime > 30) && !saveForTank && turnsLeft > STOP_SPENDING_AT_TIME && rc.hasTreeBuildRequirements() && rc.isBuildReady()) {
                // At target
                MapLocation settledLocation = plantTrees(hasSettled ? target : null);
                if (settledLocation != null) {
                    target = settledLocation;
                    hasSettled = true;
                }

                //System.out.println("Lost all trees around me, moving again")
            }

            if(!hasSettled) {
                if (!rc.hasMoved() && tryMove(target)) {
                    moveFailCounter = 0;
                } else {
                    // Couldn't move there? huh
                    moveFailCounter += 1;
                    //System.out.println("Move failed")
                }
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
            water();
            yieldAndDoBackgroundTasks();
        }
    }
}
