package bot

import battlecode.common._

abstract class Robot {
	var rc : RobotController = null
	var info : RobotType = null
	var spawnPos : MapLocation = null

	val MAP_EDGE_BROADCAST_OFFSET = 10
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

	def tryMove(to: MapLocation): Boolean = tryMove(rc.getLocation.directionTo(to))

	/**
	  * Attempts to move in a given direction, while avoiding small obstacles directly in the path.
	  *
	  * @param dir The intended direction of movement
	  * @return true if a move was performed
	  * @throws GameActionException
	  */
	def tryMove(dir: Direction): Boolean = tryMove(dir, 20, 3)

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
	def tryMove(dir: Direction, degreeOffset: Float, checksPerSide: Int): Boolean = {
		// First, try intended direction
		if (rc.canMove(dir)) {
			rc.move(dir)
			return true
		}
		// Now try a bunch of similar angles
		val moved: Boolean = false
		var currentCheck: Int = 1
		while (currentCheck <= checksPerSide) {
			{
				// Try the offset of the left side
				if (rc.canMove(dir.rotateLeftDegrees(degreeOffset * currentCheck))) {
					rc.move(dir.rotateLeftDegrees(degreeOffset * currentCheck))
					return true
				}
				// Try the offset on the right side
				if (rc.canMove(dir.rotateRightDegrees(degreeOffset * currentCheck))) {
					rc.move(dir.rotateRightDegrees(degreeOffset * currentCheck))
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
		Clock.`yield`()
	}

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
}
