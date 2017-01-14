package bot;

import battlecode.common.*;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;

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
        //var pre = Clock.getBytecodesLeft()
        Team myTeam = rc.getTeam();
        Team opponentTeam = myTeam.opponent();
        float score = 0f;
        /*for (MapLocation archon : enemyArchons) {
            score += 1f/(loc.distanceSquaredTo(archon)+1);
        }*/
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
                    score -= 5f / (dis*dis + 1);
                    score += 0.8f / (dis + 1);
                    if(dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f){
                        score -= 1000;
                    }
                }
                else if(unit.getType() == RobotType.ARCHON) {

                }
                else {
                    float dis = loc.distanceTo(unit.location);
                    score -= 3f / (dis*dis + 1);
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

        float x3 = loc.x;
        float y3 = loc.y;
        for (BulletInfo bullet : bullets) {
            float x1 = bullet.location.x - x3;
            float y1 = bullet.location.y - y3;
            float x2 = x1 + bullet.dir.getDeltaX(bullet.speed);
            float y2 = y1 + bullet.dir.getDeltaY(bullet.speed);
            float dx = x2-x1;
            float dy = y2-y1;
            double dis = (x1*dy-dx*y1)/Math.sqrt(dx*dx+dy*dy);
            if(dis < 0)
                dis = -dis;
            float dis1 = x1*x1+y1*y1;
            float dis2 = x2*x2+y2*y2;
            score -= bullet.damage * 2f / (dis2*dis2+1);
            if(dis1 > dis2)
                dis1 = dis2;
            if(dis1 > dis*dis)
                dis = Math.sqrt(dis1);
            if(dis < 1f){
                score -= 1000f*bullet.damage;
            }
        }

        //var after = Clock.getBytecodesLeft()
        //System.out.println(units.size + " units took " + (after-pre))
        return score;
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
        if (archons.length > 0) {
            int ind = rand.nextInt(archons.length);
            target = archons[ind];
        }

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

            if(rand.nextInt(200) < 1 || myLocation.distanceTo(target) < 4f){
                target = pickTarget();
            }
            System.out.println("Target: " + target.x + ", " + target.y);

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
                        if (firstUnitHit != null && firstUnitHit.isTree()) {
                            TreeInfo t = (TreeInfo) firstUnitHit;
                            if (t.getHealth() > 30f) {
                                if (rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME) {
                                    rc.fireSingleShot(rc.getLocation().directionTo(tree.location));
                                    System.out.println("Firing at tree!");
                                }
                                break;
                            }
                        }
                    }
                }
            }

            yieldAndDoBackgroundTasks();
        }
    }
}
