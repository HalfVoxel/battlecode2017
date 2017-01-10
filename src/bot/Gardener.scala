package bot

import battlecode.common._

/**
  * Created by arong on 17-1-10.
  */
class Gardener extends Robot {
	@throws[GameActionException]
	override def run() {
		System.out.println("I'm a gardener!")
		// The code you want your robot to perform every round should be in this loop
		while (true) {
			// Listen for home archon's location
			val xPos: Int = rc.readBroadcast(0)
			val yPos: Int = rc.readBroadcast(1)
			val archonLoc: MapLocation = new MapLocation(xPos, yPos)
			// Generate a random direction
			val dir: Direction = randomDirection
			// Randomly attempt to build a soldier or lumberjack in this direction
			if (rc.canBuildRobot(RobotType.SOLDIER, dir) && Math.random < .01) {
				rc.buildRobot(RobotType.SOLDIER, dir)
			}
			else if (rc.canBuildRobot(RobotType.LUMBERJACK, dir) && Math.random < .01 && rc.isBuildReady) {
				rc.buildRobot(RobotType.LUMBERJACK, dir)
			}
			// Move randomly
			tryMove(randomDirection)
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			Clock.`yield`()
		}
	}
}
