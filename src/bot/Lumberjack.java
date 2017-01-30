package bot;

import battlecode.common.*;

import static battlecode.common.GameConstants.LUMBERJACK_STRIKE_RADIUS;

class Lumberjack extends Robot {

    private float chopScore = 0;

    TreeInfo findBestTreeToChop(boolean mustBeChopable) throws GameActionException {
        TreeInfo[] trees;
        if (mustBeChopable)
            trees = rc.senseNearbyTrees(3f);
        else {
            trees = rc.senseNearbyTrees();
            if (trees.length > 50)
                trees = rc.senseNearbyTrees(5f);
        }
        TreeInfo bestTree = null;
        float bestScore = 0f;
        for (TreeInfo tree : trees) {
            if (mustBeChopable && !rc.canChop(tree.ID))
                continue;
            if (tree.team != ally) {
                float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + (float)Math.sqrt(Math.max(3f, rc.getLocation().distanceTo(tree.location)) / type.strideRadius) + 1f;
                float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / turnsToChopDown;

                if (isHighPriority(tree.ID)) {
                    score += 50;
                }

                if (tree.team == enemy) {
                    score *= 10;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestTree = tree;
                }
                /*if (!badTrees.containsKey(tree.getID()) || rc.getRoundNum() > badTrees.get(tree.getID())) {
                    // debug_setIndicatorDot(tree.location, score);

                    if (score > bestScore) {
                        bestScore = score;
                        bestTree = tree;
                    }
                }*/
            }
        }
        chopScore = bestScore * GameConstants.LUMBERJACK_CHOP_DAMAGE;

        return bestTree;
    }

    MapLocation pickTarget(MapLocation[] fallBackPositions) throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < type.strideRadius * 20) {
            // Defend!
            return highPriorityTargetPos;
        }

        if (rnd.nextFloat() < 0.2) {
            return fallBackPositions[rnd.nextInt(fallBackPositions.length)];
        } else {
            Direction dir = randomDirection();
            return clampToMap(rc.getLocation().add(dir, type.strideRadius * 10), type.sensorRadius * 0.8f);
        }
    }

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm a lumberjack and I'm okay!");
    }

    MapLocation target;

    @Override
    public void onUpdate() throws GameActionException {
        TreeInfo bestTree = findBestTreeToChop(false);
        BulletInfo[] bullets = rc.senseNearbyBullets(8f);
        if (bullets.length > 5)
            bullets = rc.senseNearbyBullets(type.strideRadius + type.bodyRadius + 3f);
        RobotInfo[] allRobots = rc.senseNearbyRobots();

        if (bestTree != null) {
            target = bestTree.location;
        } else if (rc.getRoundNum() % 10 == 0 || target == null || rc.getLocation().distanceTo(target) < type.strideRadius) {
            target = pickTarget(initialArchonLocations);
        }

        MapLocation moveTo = moveToAvoidBullets(target, bullets, allRobots);
        if (moveTo != null)
            rc.move(moveTo);

        // See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
        RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy);
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(LUMBERJACK_STRIKE_RADIUS, ally);

        markEnemySpotted(allRobots);

        if (robots.length > 0 && !rc.hasAttacked()) {
            float myValue = 0f;
            for (RobotInfo robot : friendlyRobots) {
                if (robot.ID != rc.getID()) {
                    float cost;
                    if(robot.getType() == RobotType.ARCHON){
                        cost = 150;
                    }
                    else if(robot.getType() == RobotType.LUMBERJACK){
                        cost = 90;
                    }
                    else{
                        cost = robot.getType().bulletCost;
                    }
                    myValue += cost / robot.getType().maxHealth;
                }
            }
            float opponentValue = 0f;
            for (RobotInfo robot : robots) {
                float cost;
                if(robot.getType() == RobotType.ARCHON){
                    cost = 150;
                }
                else if(robot.getType() == RobotType.LUMBERJACK){
                    cost = 90;
                }
                else{
                    cost = robot.getType().bulletCost;
                }
                opponentValue += cost / robot.getType().maxHealth;
            }
            // Use strike() to hit all nearby robots!
            if (opponentValue > myValue)
                rc.strike();
        }

        if (!rc.hasAttacked()) {
            float strikeScore = 0;
            if (friendlyRobots.length > 0)
                strikeScore -= 1000;
            TreeInfo[] treesInStrikeRange = rc.senseNearbyTrees(GameConstants.LUMBERJACK_STRIKE_RADIUS);
            for (TreeInfo tree : treesInStrikeRange) {
                if (!rc.canStrike())
                    System.out.println("Can't strike tree!!");
                float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + (float)Math.sqrt(Math.max(3f, rc.getLocation().distanceTo(tree.location)) / type.strideRadius) + 1f;
                float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / turnsToChopDown;
                if ((tree.containedRobot != null || tree.containedBullets > 0) && tree.getHealth() <= 10)
                    score -= 10000;
                if (tree.team == enemy) {
                    score *= 10;
                } else if (tree.team == ally) {
                    score *= -10;
                }
                strikeScore += score;
            }
            strikeScore *= RobotType.LUMBERJACK.attackPower;
            bestTree = findBestTreeToChop(true);
            //System.out.println("Chop score: " + chopScore);
            //System.out.println("Strike score: " + strikeScore);
            if (strikeScore > chopScore || (strikeScore > 0 && (bestTree == null || !rc.canChop(bestTree.ID)))) {
                rc.strike();
                //System.out.println("Lumberjack struck trees");
            } else if (bestTree != null && rc.canChop(bestTree.ID)) {
                rc.chop(bestTree.ID);
            }
        }

        // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
        yieldAndDoBackgroundTasks();
    }
}
