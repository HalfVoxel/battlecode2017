package bot;

import battlecode.common.*;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

class Scout extends Robot {

    boolean highPriorityTargetExists() throws GameActionException {
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        return rc.getRoundNum() < lastAttackingEnemySpotted + 20 || rc.getRoundNum() < lastGardenerSpotted + 20;
    }

    MapLocation pickTarget() throws GameActionException {
		int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
		MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
		if (rc.getRoundNum() < lastAttackingEnemySpotted + 50 && rc.getLocation().distanceTo(highPriorityTargetPos) < info.strideRadius*8) {
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
				int iterationNumber = 0;
				if(!rc.onTheMap(target) && iterationNumber < 10){
                    dir = randomDirection();
                    target = rc.getLocation().add(dir, info.sensorRadius - 1f);
                    iterationNumber += 1;
                }
                if(!rc.onTheMap(target)){
				    target = rc.getLocation();
                }
				return target;
			}
		}
    }

    float getPositionScore(MapLocation loc, MapLocation[] enemyArchons,
                         RobotInfo[] units, BulletInfo[] bullets, TreeInfo bestTree, MapLocation target) {
        Team myTeam = rc.getTeam();

        float score = 0f;
        score += 3f/(loc.distanceSquaredTo(target)+10);

        for (RobotInfo unit : units) {
            if (unit.team == myTeam) {
                if (unit.getType() == RobotType.SCOUT)
                    score -= 1f / (loc.distanceSquaredTo(unit.location) + 1);
            } else {
                if (unit.getType() == RobotType.GARDENER)
                    score += 10f / (loc.distanceSquaredTo(unit.location) + 1);
                else if(unit.getType() == RobotType.SCOUT) {
                    if(rc.getHealth() >= unit.getHealth())
                        score += 2f / (loc.distanceSquaredTo(unit.location) + 1);
                    else
                        score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                }
                else if(unit.getType() == RobotType.LUMBERJACK) {
                    float dis = loc.distanceTo(unit.location);
                    score -= 10f / (dis*dis + 1);
                    score += 0.8f / (dis + 1);
                    if(dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f){
                        score -= 1000;
                    }
                }
                else if(unit.getType() == RobotType.ARCHON) {

                }
                else {
                    float dis = loc.distanceTo(unit.location);
                    score -= 5f / (dis*dis + 1);
                    score += 0.2f / (dis + 1);
                }
            }
        }

        if(bestTree != null)
            score += (bestTree.containedBullets * 0.5) / (loc.distanceTo(bestTree.location) + 1);

        /*for (TreeInfo tree : trees){
            if(tree.getTeam() == myTeam){

            }
            else if(tree.getTeam() == opponentTeam){
                score += 0.05f / (loc.distanceSquaredTo(tree.location) + 5);
            }
            else{
                score += (tree.containedBullets * 0.5) / (loc.distanceSquaredTo(tree.location) + 1);
            }
        }*/

        score -= 1000f*getEstimatedDamageAtPosition(loc, bullets);

        //var after = Clock.getBytecodesLeft()
        //System.out.println(units.size + " units took " + (after-pre))
        return score;
    }

    /** Estimated damage from bullets when moving to the specified position */
    float getEstimatedDamageAtPosition (MapLocation loc, BulletInfo[] bullets) {
        float dmg = 0f;
        float radius = info.bodyRadius;
        for (BulletInfo bullet : bullets) {
            // Current bullet position
            float prevX = bullet.location.x - loc.x;
            float prevY = bullet.location.y - loc.y;
            float dx = bullet.dir.getDeltaX(1);
            float dy = bullet.dir.getDeltaY(1);

            // Distance the bullet has to travel to get to its closest point to #loc
            float dot = -(dx*prevX + dy*prevY);
            // Position of the closest point the bullet will be to #loc
            float closestX = prevX + dx*dot;
            float closestY = prevY + dy*dot;
            float distanceToLineOfTravel = (float)Math.sqrt(closestX*closestX + closestY*closestY);

            // The bullet cannot possibly hit us
            if (distanceToLineOfTravel > radius) continue;

            float intersectionDistDelta = (float)Math.sqrt(radius*radius - distanceToLineOfTravel*distanceToLineOfTravel);
            float intersectionDist2 = dot + intersectionDistDelta;
            if (intersectionDist2 < 0) {
                // The bullet has already passed us. Everything is ok!
                continue;
            }

            float intersectionDist1 = dot - intersectionDistDelta;

            // -1 because the bullet has not moved this frame yet
            float timeToIntersection = (intersectionDist1 / bullet.speed) - 1;

            // It will hit us this frame
            if (timeToIntersection <= 0) {
                dmg += bullet.damage;
            } else {
                // Decrease the damage further away
                dmg += 0.5f*bullet.damage / (timeToIntersection + 1);
            }
        }

        return dmg;
    }

    public void run() throws GameActionException {
        System.out.println("I'm an scout!");

        Team enemy = rc.getTeam().opponent();
        MapLocation target = rc.getLocation();
        MapLocation[] archons = rc.getInitialArchonLocations(enemy);
        int stepsWithTarget = 0;
        double targetHP = 0f;
        float fallbackRotation = 1;
        final int STOP_SPENDING_AT_TIME = 50;
        Random rand = new Random(1);
        target = randomChoice(archons);
        if (target == null) target = rc.getLocation();
        Map<Integer, Float> treeLifeMap = new HashMap<Integer, Float>();

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();
            MapLocation myLocation = rc.getLocation();
            // See if there are any nearby enemy robots
            RobotInfo[] robots = rc.senseNearbyRobots(-1, enemy);
            RobotInfo[] allRobots = rc.senseNearbyRobots();
            BulletInfo[] nearbyBullets = rc.senseNearbyBullets(8f);
            TreeInfo[] trees = rc.senseNearbyTrees();
            boolean hasMoved = false;

            // Pick a new target with a small probability or when very close to the target
            if(rand.nextInt(200) < 1 || myLocation.distanceTo(target) < 4f){
                target = pickTarget();
            }

            TreeInfo bestTree = null;
            float bestTreeScore = -1000000f;
            for (TreeInfo tree : trees){
                if(tree.getTeam() == Team.NEUTRAL){
                    float score = tree.containedBullets / (1 + myLocation.distanceTo(tree.location));
                    if(score > bestTreeScore){
                        bestTree = tree;
                        bestTreeScore = score;
                    }
                }
            }

            float bestScore = -1000000f;
            MapLocation bestMove = null;
            int iterationsDone = 0;
            List<MapLocation> movesToConsider = new ArrayList<MapLocation>();
            RobotInfo closestEnemy = null;
            float disToClosestEnemy = 1000000f;
            for (RobotInfo robot : robots) {
                if(myLocation.distanceTo(robot.location) < disToClosestEnemy){
                    disToClosestEnemy = myLocation.distanceTo(robot.location);
                    closestEnemy = robot;
                }
            }
            if(closestEnemy != null){
                Direction dir = myLocation.directionTo(closestEnemy.location);
                movesToConsider.add(myLocation.add(dir.opposite(), 2.5f));
                movesToConsider.add(myLocation.add(dir,
                        Math.max(0f, Math.min(myLocation.distanceTo(closestEnemy.location)-2.01f, 2.5f))));
            }

            while(Clock.getBytecodesLeft() > 5000){
                iterationsDone += 1;
                Direction dir = randomDirection();
                MapLocation loc = null;
                if(movesToConsider.isEmpty()) {
                    int r = rand.nextInt(10);
                    if (r < 5)
                        loc = myLocation.add(dir, 2.5f);
                    else if (r < 7)
                        loc = myLocation.add(dir, 1f);
                    else
                        loc = myLocation.add(dir, 0.2f);
                }
                else{
                    loc = movesToConsider.get(0);
                    movesToConsider.remove(0);
                }

                if(rc.canMove(loc)) {
                    float score = getPositionScore(loc, archons, allRobots, nearbyBullets, bestTree, target);
                    //System.out.println("Score = " + score)
                    if (score > bestScore) {
                        bestScore = score;
                        bestMove = loc;
                    }
                }
            }
            System.out.println("Completed " + iterationsDone + " iterations");
            if(bestMove != null){
                rc.move(bestMove);
            }

            System.out.println("stepsWithTarget = " + stepsWithTarget);
            boolean nearbyEnemyGardener = false;

            // If there are some...
            if (stepsWithTarget < 100000 && robots.length > 0) {
                stepsWithTarget += 1;
                List<Integer> bestRobotsTried = new ArrayList<Integer>();
                for(int attemptN = 0; attemptN < 4; ++attemptN) {
                    RobotInfo bestRobot = null;
                    float bestScore2 = 0;
                    MapLocation closestThreat = null;

                    for (RobotInfo robot : robots) {
                        if(bestRobotsTried.contains(robot.ID))
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
                        bestRobotsTried.add(bestRobot.ID);
                        if (bestRobot.health < targetHP) {
                            stepsWithTarget = 0;
                        }
                        targetHP = bestRobot.health;

                        BodyInfo firstUnitHit = linecast(bestRobot.location);
                        if (rc.canFireSingleShot() && rc.getLocation().distanceTo(bestRobot.location) < 2 * info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam().opponent() && turnsLeft > STOP_SPENDING_AT_TIME) {
                            rc.fireSingleShot(rc.getLocation().directionTo(bestRobot.location));
                            System.out.println("Firing!");
                        }
                    }
                    else{
                        break;
                    }
                }
            }
            if(!rc.hasAttacked()){
                float bestScore3 = -1000000f;
                RobotInfo bestRobot = null;
                for (RobotInfo robot : robots) {
                    if (robot.getType() != RobotType.SCOUT)
                        continue;
                    if(myLocation.distanceTo(robot.location) > 5.5f)
                        continue;
                    float score = 1;
                    score /= myLocation.distanceTo(robot.getLocation()) + 1;
                    if (score > bestScore3) {
                        bestScore3 = score;
                        bestRobot = robot;
                    }
                }
                if(bestRobot != null){
                    targetHP = bestRobot.health;

                    BodyInfo firstUnitHit = linecastIgnoreTrees(bestRobot.location);
                    if (rc.canFireSingleShot() && rc.getLocation().distanceTo(bestRobot.location) < 2*info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam().opponent() && turnsLeft > STOP_SPENDING_AT_TIME) {
                        rc.fireSingleShot(rc.getLocation().directionTo(bestRobot.location));
                        System.out.println("Firing despite trees!");
                    }
                }
            }
            if(!nearbyEnemyGardener && !rc.hasAttacked()) {
                for (TreeInfo tree : trees) {
                    if(Clock.getBytecodesLeft() < 2000)
                        break;
                    if (tree.getTeam() == enemy) {
                        BodyInfo firstUnitHit = linecast(tree.location);
                        if (firstUnitHit != null && firstUnitHit.isTree() && ((TreeInfo)firstUnitHit).getTeam() == enemy) {
                            TreeInfo t = (TreeInfo) firstUnitHit;
                            if(treeLifeMap.containsKey(t.getID()) && t.getHealth() < treeLifeMap.get(t.getID())) {
                                if (t.getHealth() > 30) {
                                    if (rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME) {
                                        rc.fireSingleShot(rc.getLocation().directionTo(tree.location));
                                        System.out.println("Firing at tree!");
                                    }
                                    break;
                                }
                            }
                            treeLifeMap.put(t.getID(), t.getHealth());
                        }
                    }
                }
            }

            yieldAndDoBackgroundTasks();
        }
    }
}
