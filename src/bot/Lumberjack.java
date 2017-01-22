package bot;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;

class Lumberjack extends Robot {

    private HashMap<Integer, Integer> badTrees = new HashMap<Integer, Integer>();
    private float chopScore = 0;

    TreeInfo findBestTreeToChop(boolean mustBeChopable) {
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
            if (tree.team != rc.getTeam()) {
                float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + (float)Math.sqrt(Math.max(3f, rc.getLocation().distanceTo(tree.location)) / info.strideRadius) + 1f;
                float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / turnsToChopDown;
                if (tree.getTeam() == rc.getTeam().opponent()) {
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
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < info.strideRadius * 20) {
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

    public void run() throws GameActionException {
        System.out.println("I'm a lumberjack and I'm okay!");
        Team enemy = rc.getTeam().opponent();
        MapLocation[] archons = rc.getInitialArchonLocations(enemy);
        // The code you want your robot to perform every round should be in this loop
        while (true) {
            // See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
            RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy);
            RobotInfo[] friendlyRobots = rc.senseNearbyRobots(GameConstants.LUMBERJACK_STRIKE_RADIUS, rc.getTeam());

            TreeInfo bestTree = findBestTreeToChop(false);
            BulletInfo[] bullets = rc.senseNearbyBullets(8f);
            if (bullets.length > 5)
                bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
            RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, enemy);
            MapLocation target = bestTree == null ? pickTarget(archons) : bestTree.location;
            moveToAvoidBullets(target, bullets, enemyRobots);
            if (robots.length > 0 && !rc.hasAttacked()) {
                float myValue = 0f;
                for (RobotInfo robot : friendlyRobots) {
                    if (robot.ID != rc.getID())
                        myValue += robot.getType().bulletCost / robot.getType().maxHealth;
                }
                float opponentValue = 0f;
                for (RobotInfo robot : robots) {
                    opponentValue += robot.getType().bulletCost / robot.getType().maxHealth;
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
                    float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + (float)Math.sqrt(Math.max(3f, rc.getLocation().distanceTo(tree.location)) / info.strideRadius) + 1f;
                    float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + (float)Math.sqrt(rc.getLocation().distanceTo(tree.location) / info.strideRadius) + 1) / turnsToChopDown;
                    if ((tree.containedRobot != null || tree.containedBullets > 0) && tree.getHealth() <= 10)
                        score -= 10000;
                    if (tree.getTeam() == rc.getTeam().opponent()) {
                        score *= 10;
                    } else if (tree.getTeam() == rc.getTeam()) {
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
            /*if (robots.length > 0 && !rc.hasAttacked()) {
                float myValue = 0f;
                for (RobotInfo robot : friendlyRobots) {
                    if (robot.ID != rc.getID())
                        myValue += robot.getType().bulletCost / robot.getType().maxHealth;
                }
                float opponentValue = 0f;
                for (RobotInfo robot : robots) {
                    opponentValue += robot.getType().bulletCost / robot.getType().maxHealth;
                }
                // Use strike() to hit all nearby robots!
                if (opponentValue > myValue)
                    rc.strike();
                MapLocation enemyLocation = robots[0].getLocation();
                BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
                RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, enemy);
                moveToAvoidBullets(enemyLocation, bullets, enemyRobots);
            }
            if (!rc.hasAttacked()) {
                boolean success = false;

                // No close robots, so search for robots within sight radius
                robots = rc.senseNearbyRobots(-1, enemy);
                // If there is a robot, move towards it
                if (robots.length > 0) {
                    MapLocation myLocation = rc.getLocation();
                    MapLocation enemyLocation = robots[0].getLocation();
                    Direction toEnemy = myLocation.directionTo(enemyLocation);
                    BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
                    RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, enemy);
                    moveToAvoidBullets(enemyLocation, bullets, enemyRobots);//tryMove(toEnemy);
                    success = true;
                }

                // Try to find a good tree to chop down
                boolean done = false;
                while (!done) {
                    TreeInfo bestTree = findBestTreeToChop();

                    if(!rc.hasAttacked()) {
                        float strikeScore = 0;
                        if (friendlyRobots.length > 0)
                            strikeScore -= 1000;
                        TreeInfo[] treesInStrikeRange = rc.senseNearbyTrees(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS);
                        for (TreeInfo tree : treesInStrikeRange) {
                            float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + 1f;
                            float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + (float) Math.sqrt(rc.getLocation().distanceTo(tree.location) / info.strideRadius) + 1) / turnsToChopDown;
                            if ((tree.containedRobot != null || tree.containedBullets > 0) && tree.getHealth() <= 10)
                                score -= 10000;
                            if (tree.getTeam() == rc.getTeam().opponent()) {
                                score *= 10;
                            } else if (tree.getTeam() == rc.getTeam()) {
                                score *= -10;
                            }
                            strikeScore += score;
                        }
                        strikeScore *= RobotType.LUMBERJACK.attackPower;
                        System.out.println("Chop score: " + chopScore);
                        System.out.println("Strike score: " + strikeScore);
                        if (strikeScore > chopScore) {
                            rc.strike();
                            System.out.println("Lumberjack struck trees");
                        }
                    }

                    if (bestTree != null) {
                        MapLocation myLocation = rc.getLocation();

                        Direction towards = myLocation.directionTo(bestTree.location);
                        if (rc.canChop(bestTree.ID) && !rc.hasAttacked()) {
                            rc.chop(bestTree.ID);
                            rc.setIndicatorLine(rc.getLocation(), bestTree.location, 0, 255, 0);
                            done = true;
                            success = true;
                        } else if (!rc.hasMoved()) {
                            BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
                            RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, enemy);
                            moveToAvoidBullets(bestTree.location, bullets, enemyRobots);
                            rc.setIndicatorLine(rc.getLocation(), bestTree.location, 0, 0, 0);
                            done = true;
                            success = true;
                        }
                    } else {
                        done = true;
                    }
                }

                if (!success) {
                    //tryMove(randomDirection());
                    BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
                    RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, enemy);
                    moveToAvoidBullets(null, bullets, enemyRobots);
                }
            }*/
            // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
            yieldAndDoBackgroundTasks();
        }
    }
}
