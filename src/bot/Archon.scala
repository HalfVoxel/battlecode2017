package bot

import battlecode.common._

class Archon extends Robot {

	override def run(): Unit = {
		rc.broadcast(GARDENER_OFFSET, -1000)
		rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000)

		System.out.println("I'm an archon! ")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val maxPoints = rc.getTeamVictoryPoints + Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE);
			if(maxPoints >= GameConstants.VICTORY_POINTS_TO_WIN || rc.getRoundNum == rc.getRoundLimit-1){
				val donate = Math.floor(rc.getTeamBullets/GameConstants.BULLET_EXCHANGE_RATE)*GameConstants.BULLET_EXCHANGE_RATE
				rc.donate(donate.toFloat)
			}

			val gardenerCount = spawnedCount(RobotType.GARDENER)
			var saveForTank = false
			val tankCount = spawnedCount(RobotType.TANK)

			if(rc.getTreeCount() > tankCount*4+4 && rc.getTeamBullets <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1) {
				saveForTank = true
			}

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
