package bot;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;

class Lumberjack extends Robot {

    private HashMap<Integer, Integer> badTrees = new HashMap<Integer, Integer>();

    TreeInfo findBestTreeToChop() {
        TreeInfo[] trees = rc.senseNearbyTrees();
        TreeInfo bestTree = null;
        float bestScore = 0f;
        for (TreeInfo tree : trees) {
            if (tree.team != rc.getTeam()) {
                float turnsToChopDown = (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE) + (float) Math.sqrt(rc.getLocation().distanceTo(tree.location) / info.strideRadius) + 1f;
                float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / turnsToChopDown;
                if (!badTrees.containsKey(tree.getID()) || rc.getRoundNum() > badTrees.get(tree.getID())) {
                    // setIndicatorDot(tree.location, score);

                    if (score > bestScore) {
                        bestScore = score;
                        bestTree = tree;
                    }
                }
            }
        }

        return bestTree;
    }

    public void run() throws GameActionException {
        System.out.println("I'm a lumberjack and I'm okay!");
        Team enemy = rc.getTeam().opponent();
        // The code you want your robot to perform every round should be in this loop
        while (true) {
            // See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
            RobotInfo[] robots = rc.senseNearbyRobots(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy);
            if (robots.length > 0 && !rc.hasAttacked()) {
                RobotInfo[] friendlyRobots = rc.senseNearbyRobots(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS, rc.getTeam());
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
                boolean success = false;

                // No close robots, so search for robots within sight radius
                robots = rc.senseNearbyRobots(-1, enemy);
                // If there is a robot, move towards it
                if (robots.length > 0) {
                    MapLocation myLocation = rc.getLocation();
                    MapLocation enemyLocation = robots[0].getLocation();
                    Direction toEnemy = myLocation.directionTo(enemyLocation);
                    tryMove(toEnemy);
                    success = true;
                } else {
                    // Try to find a good tree to chop down
                    boolean done = false;
                    while (!done) {
                        TreeInfo bestTree = findBestTreeToChop();
                        if (bestTree != null) {
                            MapLocation myLocation = rc.getLocation();

                            Direction towards = myLocation.directionTo(bestTree.location);
                            if (rc.canChop(bestTree.ID)) {
                                rc.chop(bestTree.ID);
                                rc.setIndicatorLine(rc.getLocation(), bestTree.location, 0, 255, 0);
                                done = true;
                                success = true;
                            } else if (tryMove(towards)) {
                                rc.setIndicatorLine(rc.getLocation(), bestTree.location, 0, 0, 0);
                                done = true;
                                success = true;
                            } else {
                                // Don't try to chop down this tree until after N turns
                                badTrees.put(bestTree.getID(), rc.getRoundNum() + 25);
                                rc.setIndicatorLine(rc.getLocation(), bestTree.location, 255, 0, 0);
                            }
                        } else {
                            done = true;
                        }
                    }
                }

                if (!success) {
                    tryMove(randomDirection());
                }
            }
            // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
            yieldAndDoBackgroundTasks();
        }
    }
}
