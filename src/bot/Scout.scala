package bot

import battlecode.common._
import java.util.Random

class Scout extends Robot {

	def pickTarget(): MapLocation = {
		var target: MapLocation = null
		val dir: Direction = randomDirection
		target = rc.getLocation.add(dir, info.strideRadius * 10)
		target
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm an scout!")
		val enemy: Team = rc.getTeam.opponent
		var target = rc.getLocation
		val archons = rc.getInitialArchonLocations(enemy)
		var stepsWithTarget = 0
		var targetHP = 0f
		if (archons.length > 0) {
			val rand = new Random(1)
			val ind = rand.nextInt(archons.length)
			target = archons(ind)
		}
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val myLocation: MapLocation = rc.getLocation
			// See if there are any nearby enemy robots
			val robots: Array[RobotInfo] = rc.senseNearbyRobots(-1, enemy)
			var hasMoved: Boolean = false
			// If there are some...
			if (stepsWithTarget < 30 && robots.length > 0) {
				stepsWithTarget += 1
				var bestRobot: RobotInfo = null
				var bestScore: Float = 0

				for (robot <- robots) {
					var score: Float = 0
					if (robot.getType == RobotType.GARDENER)
						score += 100
					else if (robot.getType == RobotType.ARCHON)
						score += 0
					else if (robot.getType == RobotType.SCOUT)
						score += 100
					score -= myLocation.distanceTo(robot.getLocation)
					if (score > bestScore) {
						bestScore = score
						bestRobot = robot
					}
				}
				if (bestRobot != null) {
					if (bestRobot.health < targetHP) {
						stepsWithTarget = 0
					}
					targetHP = bestRobot.health.toFloat
					val dir = rc.getLocation.directionTo(bestRobot.location)
					hasMoved = true
					var stride: Float = 2.5f
					if (rc.hasAttacked)
						stride = 1.3f
					while (stride > 0.05f) {
						if (!rc.hasMoved && rc.canMove(dir, stride)) {
							rc.move(dir, stride)
						}
						stride -= 0.1f
					}
					if (rc.canFireSingleShot && rc.getLocation.distanceTo(bestRobot.location) < 3.5f) {
						rc.fireSingleShot(rc.getLocation.directionTo(bestRobot.location))
					}
				}
			}
			if (!hasMoved) {
				try {
					rc.setIndicatorDot(target, 255, 0, 0)
				} catch {
					case _: Exception =>
				}
				val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 10f
				if (canSeeTarget) {
					target = pickTarget()
					stepsWithTarget = 0
				}
				val dir = myLocation.directionTo(target)
				if (rc.canMove(dir))
					tryMove(dir)
				else {
					target = pickTarget()
					tryMove(randomDirection)
				}
			}
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			yieldAndDoBackgroundTasks()
		}
	}
}
