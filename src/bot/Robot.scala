package bot

import battlecode.common._

import scala.util.Random

abstract class Robot {
	var rc : RobotController = null
	var info : RobotType = null
	var spawnPos : MapLocation = null

	val MAP_EDGE_BROADCAST_OFFSET = 10
	val HIGH_PRIORITY_TARGET_OFFSET = 20
	val GARDENER_OFFSET = 25
	var mapEdgesDetermined = 0
	var mapEdges = new Array[Float](4)
	var countingAsAlive = true

	def init(): Unit = {
		info = rc.getType
		spawnPos = rc.getLocation
	}

	def run():Unit

	/**
	  * Returns a random Direction
	  *
	  * @return a random Direction
	  */
	def randomDirection: Direction = new Direction(Math.random.toFloat * 2 * Math.PI.toFloat)

	def spawnedCount(tp: RobotType): Int = rc.readBroadcast(tp.ordinal())

	def tryMove(to: MapLocation): Boolean = {
		val dist = rc.getLocation.distanceTo(to)
		if (dist > 0) tryMove(rc.getLocation.directionTo(to), 20, 3, dist)
		else true
	}

	/**
	  * Attempts to move in a given direction, while avoiding small obstacles directly in the path.
	  *
	  * @param dir The intended direction of movement
	  * @return true if a move was performed
	  * @throws GameActionException
	  */
	def tryMove(dir: Direction): Boolean = tryMove(dir, 20, 3, info.strideRadius)

	/**
	  * Attempts to move in a given direction, while avoiding small obstacles direction in the path.
	  *
	  * @param dir           The intended direction of movement
	  * @param degreeOffset  Spacing between checked directions (degrees)
	  * @param checksPerSide Number of extra directions checked on each side, if intended direction was unavailable
	  * @return true if a move was performed
	  * @throws GameActionException
	  */
	@throws[GameActionException]
	def tryMove(dir: Direction, degreeOffset: Float, checksPerSide: Int, distance: Float): Boolean = {
		// First, try intended direction
		if (rc.canMove(dir, distance)) {
			rc.move(dir, distance)
			return true
		}
		// Now try a bunch of similar angles
		val moved: Boolean = false
		var currentCheck: Int = 1
		while (currentCheck <= checksPerSide) {
			{
				// Try the offset of the left side
				if (rc.canMove(dir.rotateLeftDegrees(degreeOffset * currentCheck), distance)) {
					rc.move(dir.rotateLeftDegrees(degreeOffset * currentCheck), distance)
					return true
				}
				// Try the offset on the right side
				if (rc.canMove(dir.rotateRightDegrees(degreeOffset * currentCheck), distance)) {
					rc.move(dir.rotateRightDegrees(degreeOffset * currentCheck), distance)
					return true
				}
				// No move performed, try slightly further
				currentCheck += 1
			}
		}
		// A move never happened, so return false.
		false
	}

	def yieldAndDoBackgroundTasks(): Unit ={
		determineMapSize()
		shakeNearbyTrees()
		updateLiveness()
		broadcastEnemyLocations()
		Clock.`yield`()
	}

	def broadcastEnemyLocations(): Unit = {
		val robots = rc.senseNearbyRobots(info.sensorRadius, rc.getTeam.opponent())

		val lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET)
		val lastGardenerSpotted = rc.readBroadcast(GARDENER_OFFSET)

		var anyHostiles = false
		var anyGardeners = false
		for (robot <- robots) {
			if (robot.getType != RobotType.ARCHON) {
				anyHostiles = true
				anyGardeners |= robot.getType == RobotType.GARDENER

				if (robot.attackCount > 0) {
					// Aaaaah! They are attacking!! High priority target
					val previousTick = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET)

					// Keep the same target for at least 5 ticks
					if (rc.getRoundNum > previousTick + 5) {
						rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, rc.getRoundNum)
						broadcast(HIGH_PRIORITY_TARGET_OFFSET + 1, robot.location)
					}
				}

				if (robot.getType == RobotType.GARDENER) {
					// Ha! Found a gardener, we can harass that one

					// Keep the same target for at least 5 ticks
					val previousTick = rc.readBroadcast(GARDENER_OFFSET)
					if (rc.getRoundNum > previousTick + 5) {
						rc.broadcast(GARDENER_OFFSET, rc.getRoundNum)
						broadcast(GARDENER_OFFSET + 1, robot.location)
					}
				}
			}
		}

		// Clear gardener and high priority target if we can see those positions but we cannot see any hostile units
		if (!anyHostiles && lastAttackingEnemySpotted != -1000 && rc.getLocation.isWithinDistance(readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET+1), info.sensorRadius*0.7f)) {
			rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000)
		}

		if (!anyGardeners && lastGardenerSpotted != -1000 && rc.getLocation.isWithinDistance(readBroadcastPosition(GARDENER_OFFSET+1), info.sensorRadius*0.7f)) {
			rc.broadcast(GARDENER_OFFSET, -1000)
		}
	}

	def broadcast (channel : Int, pos: MapLocation): Unit = {
		rc.broadcast(channel, (pos.x * 1000).toInt)
		rc.broadcast(channel + 1, (pos.y * 1000).toInt)
	}

	def readBroadcastPosition (channel : Int) = new MapLocation(rc.readBroadcast(channel) / 1000f, rc.readBroadcast(channel+1) / 1000f)

	def updateLiveness (): Unit = {
		val countAsDeadLimit = 10
		if (countingAsAlive && rc.getHealth <= countAsDeadLimit) {
			rc.broadcast(info.ordinal(), spawnedCount(info) - 1)
			countingAsAlive = false
		} else if (!countingAsAlive && rc.getHealth > countAsDeadLimit) {
			rc.broadcast(info.ordinal(), spawnedCount(info) + 1)
			countingAsAlive = true
		}
	}

	var prevMapPos : MapLocation = null
	var prevValue : Float = 0f

	def setIndicatorDot (pos : MapLocation, value : Float) {
		val origValue = value
		val origPos = pos

		if (prevMapPos == null) {
			prevMapPos = pos
			prevValue = value
		}

		val r = Math.max(Math.min(value * 3f, 1f), 0f)
		// Note b and g swapped because of a bug in the battlecode server
		val b = Math.max(Math.min((value - 1/3f) * 3f, 1f), 0f)
		val g = Math.max(Math.min((value - 2/3f) * 3f, 1f), 0f)

		rc.setIndicatorDot(prevMapPos, (r * 255f).toInt, (g * 255f).toInt, (b * 255f).toInt)

		prevValue = origValue
		prevMapPos = origPos
	}

	def shakeNearbyTrees (): Unit = {
		if (rc.canShake) {
			val trees = rc.senseNearbyTrees(info.bodyRadius + info.strideRadius)
			var bestTree: TreeInfo = null
			for (tree <- trees) {
				// Make sure it is not the tree of an opponent
				if (!tree.team.isPlayer || tree.team == rc.getTeam) {
					// Make sure the tree has bullets and pick the tree with the most bullets in it
					if (tree.containedBullets > 1 && (bestTree == null || tree.containedBullets > bestTree.containedBullets) && rc.canShake(tree.getID)) {
						bestTree = tree
					}
				}
			}

			if (bestTree != null) {
				rc.shake(bestTree.getID)
			}
		}
	}

	def determineMapSize (): Unit = {
			// Abort if all map edges have already been determined
			if (mapEdgesDetermined == 0xF) return

			val globalMapEdgesDetermined = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET)
			if (globalMapEdgesDetermined != mapEdgesDetermined) {
				mapEdgesDetermined = globalMapEdgesDetermined
				for (i <- 0 until 4) {
					mapEdges(i) = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1) / 1000f
				}
			}

			var tmpDetermined = mapEdgesDetermined
			for (i <- 0 until 4) {
				if ((mapEdgesDetermined & (1 << i)) == 0) {
					val angle = i * Math.PI.toFloat / 4f
					if (!rc.onTheMap(rc.getLocation.add(angle, info.sensorRadius * 0.99f))) {
						// Found map edge
						var mn = 0f
						var mx = info.sensorRadius * 0.99f
						while (mx - mn > 0.002f) {
							val mid = (mn + mx) / 2
							if (rc.onTheMap(rc.getLocation.add(angle, mid))) {
								mn = mid
							} else {
								mx = mid
							}
						}

						val result = (if (i % 2 == 0) rc.getLocation.x else rc.getLocation.y) + mn
						rc.broadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1, (result * 1000).toInt)
						// This robot will pick up the change for real the next time determineMapSize is called
						tmpDetermined |= (1 << i)
						rc.broadcast(MAP_EDGE_BROADCAST_OFFSET, tmpDetermined)
						System.out.println("Found map edge " + i + " at " + result)
					}
				}
			}
	}

	/**
	  * A slightly more complicated example function, this returns true if the given bullet is on a collision
	  * course with the current robot. Doesn't take into account objects between the bullet and this robot.
	  *
	  * @param bullet The bullet in question
	  * @return True if the line of the bullet's path intersects with this robot's current position.
	  */
	def willCollideWithMe(bullet: BulletInfo): Boolean = {
		val myLocation: MapLocation = rc.getLocation
		// Get relevant bullet information
		val propagationDirection: Direction = bullet.dir
		val bulletLocation: MapLocation = bullet.location
		// Calculate bullet relations to this robot
		val directionToRobot: Direction = bulletLocation.directionTo(myLocation)
		val distToRobot: Float = bulletLocation.distanceTo(myLocation)
		val theta: Float = propagationDirection.radiansBetween(directionToRobot)
		// If theta > 90 degrees, then the bullet is traveling away from us and we can break early
		if (Math.abs(theta) > Math.PI / 2) {
			return false
		}
		// distToRobot is our hypotenuse, theta is our angle, and we want to know this length of the opposite leg.
		// This is the distance of a line that goes from myLocation and intersects perpendicularly with propagationDirection.
		// This corresponds to the smallest radius circle centered at our location that would intersect with the
		// line that is the path of the bullet.
		val perpendicularDist: Float = Math.abs(distToRobot * Math.sin(theta)).toFloat // soh cah toa :)
		return (perpendicularDist <= rc.getType.bodyRadius)
	}

	/** Return value in [bullets/tick] */
	def treeScore (tree: TreeInfo, fromPos : MapLocation = null): Float = {
		var turnsToChopDown = 1f
		turnsToChopDown += (tree.health/GameConstants.LUMBERJACK_CHOP_DAMAGE)
		if (fromPos != null) turnsToChopDown += Math.sqrt(fromPos.distanceTo(tree.location)/info.strideRadius).toFloat

		val score = ((if (tree.containedRobot != null) tree.containedRobot.bulletCost * 1.5f else 0) + tree.containedBullets + 1) / turnsToChopDown
		score
	}

	def teamOf (b: BodyInfo): Team = {
		b match {
			case r:RobotInfo => r.team
			case t:TreeInfo => t.team
			case _ => Team.NEUTRAL
		}
	}

	def linecast (b: MapLocation): BodyInfo = {
		val a = rc.getLocation
		val dist = Math.min(a.distanceTo(b), info.sensorRadius * 0.99f)
		if (dist == 0) {
			// TODO: Sense current position
			return null
		}

		val steps = (dist / 0.7f).toInt
		val dir = a.directionTo(b)
		for (t <- 1 to steps) {
			val p = a.add(dir, dist * t / steps.toFloat)
			val robot = rc.senseRobotAtLocation(p)
			if (robot != null && robot.ID != rc.getID) return robot

			val tree = rc.senseTreeAtLocation(p)
			if (tree != null) return tree
		}

		null
	}
}
