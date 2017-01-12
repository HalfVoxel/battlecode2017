package bot

import battlecode.common._

class Lumberjack extends Robot {
	@throws[GameActionException]
	override def run() {
		System.out.println("I'm a lumberjack and I'm okay!")
		val enemy: Team = rc.getTeam.opponent
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			// See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
			var robots: Array[RobotInfo] = rc.senseNearbyRobots(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy)
			val trees = rc.senseNearbyTrees()
			var closestTree: TreeInfo = null
			var smallestDistance: Float = 1000;
			for (tree <- trees) {
				if (tree.team != rc.getTeam && rc.getLocation.distanceTo(tree.location) < smallestDistance){
					smallestDistance = rc.getLocation.distanceTo(tree.location)
					closestTree = tree
				}
			}
			if (closestTree != null) {
				val myLocation: MapLocation = rc.getLocation
				rc.setIndicatorDot(closestTree.location, 255, 0, 0)
				val towards: Direction = myLocation.directionTo(closestTree.location)
				tryMove(towards)
				if(rc.canChop(closestTree.ID)){
					rc.chop(closestTree.ID)
				}
			}
			else if (robots.length > 0 && !rc.hasAttacked) {
				// Use strike() to hit all nearby robots!
				rc.strike()
			} else {
				// No close robots, so search for robots within sight radius
				robots = rc.senseNearbyRobots(-1, enemy)
				// If there is a robot, move towards it
				if (robots.length > 0) {
					val myLocation: MapLocation = rc.getLocation
					val enemyLocation: MapLocation = robots(0).getLocation
					val toEnemy: Direction = myLocation.directionTo(enemyLocation)
					tryMove(toEnemy)
				} else {
					// Move Randomly
					tryMove(randomDirection)
				}
			}
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			Clock.`yield`()
		}
	}
}
