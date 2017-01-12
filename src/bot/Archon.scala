package bot

import battlecode.common._

class Archon extends Robot {
	var gardeners = 0

	override def run(): Unit = {
		System.out.println("I'm an archon!")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val maxPoints = rc.getTeamVictoryPoints + Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE);
			if(maxPoints >= GameConstants.VICTORY_POINTS_TO_WIN || rc.getRoundNum == rc.getRoundLimit-1){
				val donate = Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE)*GameConstants.BULLET_EXCHANGE_RATE
				rc.donate(donate.toFloat)
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

			yieldAndDoBackgroundTasks()
		}
	}
}
