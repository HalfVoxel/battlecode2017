package bot

import battlecode.common._

/**
  * Created by arong on 17-1-10.
  */
abstract class Robot {
	var rc : RobotController = null

	def run():Unit

	/**
	  * Returns a random Direction
	  *
	  * @return a random Direction
	  */
	def randomDirection: Direction = new Direction(Math.random.toFloat * 2 * Math.PI.toFloat)

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
