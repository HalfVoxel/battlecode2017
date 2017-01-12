package bot

import battlecode.common._

class Lumberjack extends Robot {

	private val badTrees = scala.collection.mutable.Map[Int,Int]()

	def findBestTreeToChop() = {
		val trees = rc.senseNearbyTrees()
		var bestTree: TreeInfo = null
		var bestScore = 0f
		for (tree <- trees) {
			if (tree.team != rc.getTeam) {
				val turnsToChopDown = (tree.health/GameConstants.LUMBERJACK_CHOP_DAMAGE) + Math.sqrt(rc.getLocation.distanceTo(tree.location)/info.strideRadius).toFloat + 1f
				val score = ((if (tree.containedRobot != null) tree.containedRobot.bulletCost * 1.5f else 0) + tree.containedBullets + 1) / turnsToChopDown
				if (!badTrees.contains(tree.getID) || rc.getRoundNum > badTrees(tree.getID)) {
					// setIndicatorDot(tree.location, score)

					if (score > bestScore) {
						bestScore = score
						bestTree = tree
					}
				}
			}
		}

		bestTree
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm a lumberjack and I'm okay!")
		val enemy: Team = rc.getTeam.opponent
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			// See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
			var robots: Array[RobotInfo] = rc.senseNearbyRobots(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy)
			if (robots.length > 0 && !rc.hasAttacked) {
				// Use strike() to hit all nearby robots!
				rc.strike()
			} else {
				// Try to find a good tree to chop down
				var done = false
				var success = false
				while(!done) {
					val bestTree = findBestTreeToChop()
					if (bestTree != null) {
						val myLocation: MapLocation = rc.getLocation

						val towards: Direction = myLocation.directionTo(bestTree.location)
						if (rc.canChop(bestTree.ID)) {
							rc.chop(bestTree.ID)
							rc.setIndicatorLine(rc.getLocation, bestTree.location, 0, 255, 0)
							done = true
							success = true
						} else if (tryMove(towards)) {
							rc.setIndicatorLine(rc.getLocation, bestTree.location, 0, 0, 0)
							done = true
							success = true
						} else {
							// Don't try to chop down this tree until after N turns
							badTrees(bestTree.getID) = rc.getRoundNum + 25
							rc.setIndicatorLine(rc.getLocation, bestTree.location, 255, 0, 0)
						}
					} else {
						done = true
					}
				}

				if (!success) {
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
			}
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			yieldAndDoBackgroundTasks()
		}
	}
}
