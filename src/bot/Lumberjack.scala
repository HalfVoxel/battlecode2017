package bot

import battlecode.common._

/**
  * Created by arong on 17-1-10.
  */
class Lumberjack extends Robot {
	@throws[GameActionException]
	override def run() {
		System.out.println("I'm a lumberjack!")
		val enemy: Team = rc.getTeam.opponent
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			// See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
			var robots: Array[RobotInfo] = rc.senseNearbyRobots(RobotType.LUMBERJACK.bodyRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS, enemy)
			if (robots.length > 0 && !rc.hasAttacked) {
				// Use strike() to hit all nearby robots!
				rc.strike()
			}
			else {
				// No close robots, so search for robots within sight radius
				robots = rc.senseNearbyRobots(-1, enemy)
				// If there is a robot, move towards it
				if (robots.length > 0) {
					val myLocation: MapLocation = rc.getLocation
					val enemyLocation: MapLocation = robots(0).getLocation
					val toEnemy: Direction = myLocation.directionTo(enemyLocation)
					tryMove(toEnemy)
				}
				else {
					// Move Randomly
					tryMove(randomDirection)
				}
			}
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			Clock.`yield`()
		}
	}
}
