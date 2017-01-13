package bot

import battlecode.common._
import java.util.Random

class Scout extends Robot {

	def highPriorityTargetExists(): Boolean = {
		val lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET)
		val lastGardenerSpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET)
		rc.getRoundNum < lastAttackingEnemySpotted + 20 || rc.getRoundNum < lastGardenerSpotted + 20
	}

	def pickTarget(): MapLocation = {
		val lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET)
		val highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1)
		if (rc.getRoundNum < lastAttackingEnemySpotted + 50 && rc.getLocation.distanceTo(highPriorityTargetPos) < info.strideRadius*8) {
			// Defend!
			highPriorityTargetPos
		} else {
			val lastTimeGardenerSpotted = rc.readBroadcast(GARDENER_OFFSET)
			if (rc.getRoundNum < lastTimeGardenerSpotted + 50) {
				// Follow that gardener!
				readBroadcastPosition(GARDENER_OFFSET + 1)
			} else {
				val dir: Direction = randomDirection
				val target = rc.getLocation.add(dir, info.strideRadius * 10)
				target
			}
		}
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm an scout!")
		val enemy: Team = rc.getTeam.opponent
		var target = rc.getLocation
		val archons = rc.getInitialArchonLocations(enemy)
		var stepsWithTarget = 0
		var targetHP = 0f
		var fallbackRotation = 1
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
				var closestThreat: MapLocation = null

				for (robot <- robots) {
					var score: Float = 0
					if (robot.getType == RobotType.GARDENER)
						score += 100
					else if (robot.getType == RobotType.ARCHON)
						score += (if(highPriorityTargetExists() || rc.getRoundNum < 2000) 0f else 1f)
					else if (robot.getType == RobotType.SCOUT)
						score += 100
					if(robot.getType == RobotType.LUMBERJACK || robot.getType == RobotType.SOLDIER || robot.getType == RobotType.TANK){
						if(closestThreat == null || robot.location.distanceTo(myLocation) < closestThreat.distanceTo(myLocation)){
							closestThreat = robot.location
						}
						score += 50
					}
					score /= myLocation.distanceTo(robot.getLocation) + 1
					if (score > bestScore) {
						bestScore = score
						bestRobot = robot
					}
				}
				if(closestThreat != null && closestThreat.distanceTo(myLocation) < 15f){
					var dir = closestThreat.directionTo(myLocation).rotateLeftDegrees(70)
					tryMove(dir)
				}
				if (bestRobot != null) {
					if (bestRobot.health < targetHP) {
						stepsWithTarget = 0
					}

					hasMoved = true
					var triedToMove = false
					// If we have line of sight to the enemy don't move closer
					var firstUnitHit = linecast(bestRobot.location)
					if (firstUnitHit == null || firstUnitHit.isTree || teamOf(firstUnitHit) != enemy || bestRobot.getType == RobotType.GARDENER) {
						triedToMove = true
						targetHP = bestRobot.health.toFloat
						val dir = rc.getLocation.directionTo(bestRobot.location)
						var stride: Float = 2.5f
						if (rc.hasAttacked)
							stride = 1.3f
						while (stride > 0.05f) {
							if (!rc.hasMoved && rc.canMove(dir, stride)) {
								rc.move(dir, stride)
							}
							stride -= 0.1f
						}
					}

					// Move in an arc
					val nearbyEnemiesThatCanAttack = rc.senseNearbyRobots(info.sensorRadius, enemy).exists(e => e.getType != RobotType.ARCHON && e.getType != RobotType.GARDENER)
					if (!rc.hasMoved && (triedToMove || nearbyEnemiesThatCanAttack)) {
						val targetDir = bestRobot.location.directionTo(rc.getLocation).rotateLeftRads(fallbackRotation * info.strideRadius / rc.getLocation.distanceTo(bestRobot.location))
						val targetPoint = bestRobot.location.add(targetDir)
						if (!tryMove(targetPoint)) {
							fallbackRotation = -fallbackRotation
						}
					}

					// Linecast again after we moved
					firstUnitHit = linecast(bestRobot.location)
					if (rc.canFireSingleShot && rc.getLocation.distanceTo(bestRobot.location) < 2*info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam.opponent) {
						rc.fireSingleShot(rc.getLocation.directionTo(bestRobot.location))
					}
				}
			}
			if (!rc.hasMoved && !hasMoved) {
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

				if (!tryMove(target)) {
					target = pickTarget()
					tryMove(randomDirection)
				}
			}
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			yieldAndDoBackgroundTasks()
		}
	}
}
