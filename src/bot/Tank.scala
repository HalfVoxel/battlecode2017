package bot

import battlecode.common._
import scala.util.Random

class Tank extends Robot {

	def pickTarget (fallBackPositions : Array[MapLocation]): MapLocation = {
		if (Random.nextFloat() < 0.2) {
			fallBackPositions(Random.nextInt(fallBackPositions.length))
		} else {
			val dir: Direction = randomDirection
			rc.getLocation.add(dir, info.strideRadius * 10)
		}
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm an tank!")

		val enemy = rc.getTeam.opponent
		var target = rc.getLocation
		val archons = rc.getInitialArchonLocations(enemy)
		var stepsWithTarget = 0
		val STOP_SPENDING_AT_TIME = 50

		if(archons.length > 0) {
			val rand = new Random(1)
			val ind = rand.nextInt(archons.length)
			target = archons(ind)
		}

		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val turnsLeft = rc.getRoundLimit - rc.getRoundNum
			// See if there are any nearby enemy robots
			val robots: Array[RobotInfo] = rc.senseNearbyRobots(-1, enemy)
			val friendlyRobots: Array[RobotInfo] = rc.senseNearbyRobots(-1, rc.getTeam)

			if(robots.length > 0){
				val myLocation: MapLocation = rc.getLocation
				val enemyLocation: MapLocation = robots(0).getLocation
				val toEnemy: Direction = myLocation.directionTo(enemyLocation)
				tryMove(toEnemy)
			}

			if(!rc.hasMoved) {
				try {
					rc.setIndicatorDot(target, 255, 0, 0)
				} catch {
					case _:Exception =>
				}
				val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 10f
				if (canSeeTarget) {
					target = pickTarget(archons)
					stepsWithTarget = 0
				}

				if (!tryMove(target)) {
					target = pickTarget(archons)
					tryMove(target)
				}
			}

			// If there are some...
			if (robots.length > 0 && turnsLeft > STOP_SPENDING_AT_TIME) {
				if (rc.canFirePentadShot && friendlyRobots.length < robots.length) {
					// ...Then fire a bullet in the direction of the enemy.
					rc.firePentadShot(rc.getLocation.directionTo(robots(0).location))
				}
				// And we have enough bullets, and haven't attacked yet this turn...
				if (rc.canFireSingleShot) {
					// ...Then fire a bullet in the direction of the enemy.
					rc.fireSingleShot(rc.getLocation.directionTo(robots(0).location))
				}
			}

			yieldAndDoBackgroundTasks()
		}
	}
}
