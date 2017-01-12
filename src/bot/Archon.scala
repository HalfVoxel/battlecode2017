package bot

import battlecode.common.{Clock, Direction, MapLocation, RobotType}

class Archon extends Robot {
	var gardeners = 0

	override def run(): Unit = {
		System.out.println("I'm an archon!")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			if (rc.getTeamBullets > 1000) {
				rc.donate(500)
			}

			val gardenerCount = spawnedCount(RobotType.GARDENER)
			if (gardenerCount < 2 || 3*rc.getTreeCount > gardenerCount) {
				// Generate a random direction
				val dir: Direction = randomDirection
				if (rc.canHireGardener(dir)) {
					rc.hireGardener(dir)
					gardeners += 1
					rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1)
				}
			}

			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			yieldAndDoBackgroundTasks()
		}
	}
}
