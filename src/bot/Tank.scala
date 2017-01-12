package bot

import java.util.Random

import battlecode.common._

class Tank extends Robot {

	def pickTarget (): MapLocation = {
		var target : MapLocation = null
		val dir: Direction = randomDirection
		target = rc.getLocation.add(dir, info.strideRadius * 10)
		target
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm an soldier!")

		val enemy: Team = rc.getTeam.opponent
		var target = rc.getLocation
		var archons = rc.getInitialArchonLocations(enemy)
		var stepsWithTarget = 0
		var targetHP = 0f
		if(archons.length > 0) {
			val rand = new Random(1)
			val ind = rand.nextInt(archons.length)
			target = archons(ind)
		}
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val myLocation: MapLocation = rc.getLocation
			// See if there are any nearby enemy robots
			val robots: Array[RobotInfo] = rc.senseNearbyRobots(-1, enemy)

			if(!rc.hasMoved) {
				try {
					rc.setIndicatorDot(target, 255, 0, 0)
				} catch {
					case _:Exception =>
				}
				val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 10f
				if (canSeeTarget) {
					target = pickTarget()
					stepsWithTarget = 0
				}
				var dir = myLocation.directionTo(target)
				if (rc.canMove(dir))
					tryMove(dir)
				else {
					target = pickTarget()
					tryMove(randomDirection)
				}
			}
			// If there are some...
			if (robots.length > 0) {
				if (rc.canFirePentadShot) {
					// ...Then fire a bullet in the direction of the enemy.
					rc.firePentadShot(rc.getLocation.directionTo(robots(0).location))
				}
				// And we have enough bullets, and haven't attacked yet this turn...
				if (rc.canFireSingleShot) {
					// ...Then fire a bullet in the direction of the enemy.
					rc.fireSingleShot(rc.getLocation.directionTo(robots(0).location))
				}
			}
			else{
			}

			yieldAndDoBackgroundTasks()
		}
	}
}
