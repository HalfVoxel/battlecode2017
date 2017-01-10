package bot

import battlecode.common.{Clock, Direction, MapLocation}

/**
  * Created by arong on 17-1-10.
  */
class Archon extends Robot {
	override def run(): Unit = {
		System.out.println("I'm an archon!")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			// Generate a random direction
			val dir: Direction = randomDirection
			// Randomly attempt to build a gardener in this direction
			if (rc.canHireGardener(dir) && Math.random < .01) {
				rc.hireGardener(dir)
			}
			// Move randomly
			tryMove(randomDirection)
			// Broadcast archon's location for other robots on the team to know
			val myLocation: MapLocation = rc.getLocation
			rc.broadcast(0, myLocation.x.toInt)
			rc.broadcast(1, myLocation.y.toInt)
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			Clock.`yield`()
		}
	}
}
