package bot

import battlecode.common._

class Soldier extends Robot {
	@throws[GameActionException]
	override def run() {
		System.out.println("I'm an soldier!")
		val enemy: Team = rc.getTeam.opponent
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val myLocation: MapLocation = rc.getLocation
			// See if there are any nearby enemy robots
			val robots: Array[RobotInfo] = rc.senseNearbyRobots(-1, enemy)
			// If there are some...
			if (robots.length > 0) {
				// And we have enough bullets, and haven't attacked yet this turn...
				if (rc.canFireSingleShot) {
					// ...Then fire a bullet in the direction of the enemy.
					rc.fireSingleShot(rc.getLocation.directionTo(robots(0).location))
				}
			}
			// Move randomly
			tryMove(randomDirection)
			yieldAndDoBackgroundTasks()
		}
	}
}
