package bot

import battlecode.common._

object RobotPlayer {
	/**
	  * run() is the method that is called when a robot is instantiated in the Battlecode world.
	  * If this method returns, the robot dies!
	  **/
	@SuppressWarnings(Array("unused"))
	@throws[GameActionException]
	def run(rc: RobotController) {
		// Here, we've separated the controls into a different method for each RobotType.
		try {
			val robot = rc.getType match {
				case RobotType.ARCHON => new Archon()
				case RobotType.GARDENER => new Gardener()
				case RobotType.SOLDIER => new Soldier()
				case RobotType.LUMBERJACK => new Lumberjack()
				case RobotType.SCOUT => new Scout()
				case _ => return
			}

			robot.rc = rc
			robot.init()
			robot.run()
		} catch {
			case e: Exception => {
				System.out.println("Exception in " + rc.getType)
				e.printStackTrace()

				// Initialize the unit completely from scratch
				//run(rc)
			}
		}
	}
}
