package bot

import battlecode.common._
import java.util.Random
import scala.collection.mutable.ListBuffer

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

	def getPositionScore(loc: MapLocation, enemyArchons: Array[MapLocation],
											 units: Array[RobotInfo], bullets: Array[BulletInfo]): Float = {
		//var pre = Clock.getBytecodesLeft()
		var myTeam = rc.getTeam
		var score = 0f
		var i = 0
		while(i < enemyArchons.length){
			var archon = enemyArchons(i)
			score += 1f/(loc.distanceSquaredTo(archon)+1)
			i += 1
		}
		i = 0
		while(i < units.length){
			var unit = units(i)
			if (unit.team == myTeam) {
				if (unit.getType == RobotType.SCOUT)
					score -= 1f / (loc.distanceSquaredTo(unit.location) + 1)
			}
			else {
				if (unit.getType == RobotType.GARDENER)
					score += 10f / (loc.distanceSquaredTo(unit.location) + 1)
				else if(unit.getType == RobotType.SCOUT)
					score += 2f / (loc.distanceSquaredTo(unit.location) + 1)
				else if(unit.getType == RobotType.LUMBERJACK)
					score -= 20f / (loc.distanceSquaredTo(unit.location) + 1)
				else
					score -= 5f / (loc.distanceSquaredTo(unit.location) + 1)
			}
			i += 1
		}
		i = 0
		val x3 = loc.x
		val y3 = loc.y
		while(i < bullets.length){
			val bullet = bullets(i)
			val x1 = bullet.location.x - x3
			val y1 = bullet.location.y - y3
			val x2 = x1 + bullet.dir.getDeltaX(bullet.speed)
			val y2 = y1 + bullet.dir.getDeltaY(bullet.speed)
			//System.out.println("(" + x1 + ", " + y1 + "), (" + x2 + ", " + y2 + ")")
			val dx = x2-x1
			val dy = y2-y1
			var dis = (x1*dy-dx*y1)/Math.sqrt(dx*dx+dy*dy)
			if(dis < 0)
				dis = -dis
			//System.out.println("dis = " + dis)
			var dis1 = x1*x1+y1*y1
			//System.out.println("dis1 = " + Math.sqrt(dis1))
			var dis2 = x2*x2+y2*y2
			//System.out.println("dis2 = " + Math.sqrt(dis2))
			if(dis1 > dis2)
				dis1 = dis2
			if(dis1 > dis*dis)
				dis = Math.sqrt(dis1)
			//System.out.println("disf = " + dis)
			//if(loc.distanceTo(bullet.location) < 1f+bullet.speed){
			if(dis < 1f){
				score -= 1000f*bullets(i).damage
			}
			i += 1
		}

		//var after = Clock.getBytecodesLeft()
		//System.out.println(units.size + " units took " + (after-pre))
		score
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
		val STOP_SPENDING_AT_TIME = 50
		val rand = new Random(1)
		if (archons.length > 0) {
			val ind = rand.nextInt(archons.length)
			target = archons(ind)
		}
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			val turnsLeft = rc.getRoundLimit - rc.getRoundNum
			val myLocation: MapLocation = rc.getLocation
			// See if there are any nearby enemy robots
			val robots: Array[RobotInfo] = rc.senseNearbyRobots(-1, enemy)
			val allRobots = rc.senseNearbyRobots()
			val nearbyBullets = rc.senseNearbyBullets(8f)
			var hasMoved: Boolean = false

			var bestScore = -1000000f
			var bestMove: MapLocation = null
			var iterationsDone = 0
			while(Clock.getBytecodesLeft() > 5000){
				iterationsDone += 1
				var dir = randomDirection
				var loc: MapLocation = null
				val r = rand.nextInt(10)
				if(r < 5)
					loc = myLocation.add(dir, 2.5f)
				else if(r < 7)
					loc = myLocation.add(dir, 1f)
				else
					loc = myLocation.add(dir, 0.2f)
				if(rc.canMove(loc)) {
					var score = getPositionScore(loc, archons, allRobots, nearbyBullets)
					//System.out.println("Score = " + score)
					if (score > bestScore) {
						bestScore = score
						bestMove = loc
					}
				}
			}
			//System.out.println("Completed " + iterationsDone + " iterations")
			if(bestMove != null){
				rc.move(bestMove)
			}

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
				/*if(closestThreat != null && closestThreat.distanceTo(myLocation) < 15f){
					var dir = closestThreat.directionTo(myLocation).rotateLeftDegrees(70)
					tryMove(dir)
				}*/
				if (bestRobot != null) {
					if (bestRobot.health < targetHP) {
						stepsWithTarget = 0
					}
					targetHP = bestRobot.health

					/*hasMoved = true
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
					}*/

					//System.out.println("Can fire?")
					// Linecast again after we moved
					var firstUnitHit = linecast(bestRobot.location)
					if (rc.canFireSingleShot && rc.getLocation.distanceTo(bestRobot.location) < 2*info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam.opponent && turnsLeft > STOP_SPENDING_AT_TIME) {
						rc.fireSingleShot(rc.getLocation.directionTo(bestRobot.location))
						//System.out.println("Firing!")
					}
				}
			}
			/*if (!rc.hasMoved && !hasMoved) {
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
			}*/
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			yieldAndDoBackgroundTasks()
		}
	}
}
