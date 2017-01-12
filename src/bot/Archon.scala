package bot

import battlecode.common._

class Archon extends Robot {

	override def run(): Unit = {
		System.out.println("I'm an archon!")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val maxPoints = rc.getTeamVictoryPoints + Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE);
			if(maxPoints >= GameConstants.VICTORY_POINTS_TO_WIN || rc.getRoundNum == rc.getRoundLimit-1){
				val donate = Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE)*GameConstants.BULLET_EXCHANGE_RATE
				rc.donate(donate.toFloat)
			}

			var saveForTank = false
			val tankCount = spawnedCount(RobotType.TANK)

			if(rc.getTreeCount() > tankCount*4+4 && rc.getTeamBullets < 500){
				saveForTank = true
			}

			val gardenerCount = spawnedCount(RobotType.GARDENER)
			if ((gardenerCount < 1 || rc.getTreeCount > 3*gardenerCount) && !saveForTank) {
				// Generate a random direction
				val dir: Direction = randomDirection
				if (rc.canHireGardener(dir)) {
					rc.hireGardener(dir)
					rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1)
				}
			}

			yieldAndDoBackgroundTasks()
		}
	}
}
