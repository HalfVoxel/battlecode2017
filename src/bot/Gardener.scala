package bot

import java.util.Random

import battlecode.common._

class Gardener extends Robot {

	def water (): Unit = {
		if (rc.canWater) {
			val trees = rc.senseNearbyTrees(2*info.bodyRadius, rc.getTeam)
			var minHealthTree: TreeInfo = null
			for (tree <- trees) {
				if (minHealthTree == null || tree.health < minHealthTree.health && rc.canWater(tree.getID)) {
					minHealthTree = tree
				}
			}

			if (minHealthTree != null) {
				rc.water(minHealthTree.getID)
			}
		}

		shakeNearbyTrees()
	}

	def likelyValidTarget (target : MapLocation, freeRadius : Float): Boolean = {
		if (spawnPos.isWithinDistance(target, info.bodyRadius*8)) {
			return false
		}

		val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 0.01f || rc.canSenseAllOfCircle(target, freeRadius)

		if (canSeeTarget && (rc.isCircleOccupiedExceptByThisRobot(target.add(0, 0.001f), freeRadius) || !rc.onTheMap(target.add(0, 0.001f), info.bodyRadius + GameConstants.BULLET_TREE_RADIUS))) {
			return false
		}

		true
	}

	def pickTarget (freeRadius : Float): MapLocation = {
		var target : MapLocation = null
		var tests : Int = 0
		do {
			// Pick a new target
			// Generate a random direction
			val dir: Direction = randomDirection
			target = rc.getLocation.add(dir, info.strideRadius * 2)

			// Ensure it is far away from the spawn pos
			target = spawnPos.add(spawnPos.directionTo(target), Math.max(spawnPos.distanceTo(target), info.bodyRadius*8))

			tests += 1
		} while (tests < 10 && !likelyValidTarget(target, freeRadius))

		target
	}

	def buildLumberjackInDenseForests(): Unit = {
		val neutralTreeCount = rc.senseNearbyTrees(info.sensorRadius, Team.NEUTRAL).length
		if (neutralTreeCount >= 3 && spawnedCount(RobotType.LUMBERJACK) < 2) {
			// Create a woodcutter
			var done = false
			while(!done) {
				val dir: Direction = randomDirection
				if (rc.canBuildRobot(RobotType.LUMBERJACK, dir)) {
					rc.buildRobot(RobotType.LUMBERJACK, dir)
					rc.broadcast(RobotType.LUMBERJACK.ordinal(), spawnedCount(RobotType.LUMBERJACK) + 1)
					done = true
				} else {
					yieldAndDoBackgroundTasks()
				}
			}
		}
	}

	@throws[GameActionException]
	override def run() {
		System.out.println("I'm a gardener!")

		// The code you want your robot to perform every round should be in this loop

		var target = rc.getLocation
		val desiredRadius = info.bodyRadius + 2.01f*GameConstants.BULLET_TREE_RADIUS;
		var moveFailCounter = 0
		var hasBuiltScout = false
		val rand = new Random(1)
		val ind = rand.nextInt(5)

		buildLumberjackInDenseForests()

		while (true) {
			var saveForTank = false
			val tankCount = spawnedCount(RobotType.TANK)
			val lumberjackCount = spawnedCount(RobotType.LUMBERJACK)
			if(rc.getTreeCount() > tankCount*4+4){
				saveForTank = true
			}

			var invalidTarget = moveFailCounter > 5 || !likelyValidTarget(target, desiredRadius)
			val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 0.01f || rc.canSenseAllOfCircle(target, desiredRadius)

			var dir = randomDirection;
			if (!hasBuiltScout && rc.canBuildRobot(RobotType.SCOUT, dir) && !saveForTank){
				rc.buildRobot(RobotType.SCOUT, dir);
				hasBuiltScout = true
			}
			if (invalidTarget) {
				target = pickTarget(desiredRadius)
				//System.out.println("Picked new target " + target)
				moveFailCounter = 0
				try {
					rc.setIndicatorDot(target, 255, 0, 0)
				} catch {
					case _:Exception =>
				}
			}

			if (canSeeTarget && !invalidTarget && rc.getLocation.distanceSquaredTo(target) < 0.001f) {
				// At target

				for (i <- 0 until 6) {
					while (!rc.hasTreeBuildRequirements || !rc.isBuildReady) {
						water()
						yieldAndDoBackgroundTasks()
					}

					val dir = new Direction(2*Math.PI.toFloat*i / 6f)
					if (rc.canBuildRobot(RobotType.TANK, dir)) {
						rc.buildRobot(RobotType.TANK, dir)
						rc.broadcast(RobotType.TANK.ordinal(), tankCount + 1)
					}
					if (rc.canBuildRobot(RobotType.LUMBERJACK, dir) && lumberjackCount < 2) {
						rc.buildRobot(RobotType.LUMBERJACK, dir)
						rc.broadcast(RobotType.LUMBERJACK.ordinal(), lumberjackCount + 1)
					}
					if (rc.canPlantTree(dir) && !saveForTank) {
						rc.plantTree(dir)
						System.out.println("Planted tree")
						System.out.println("Detected: " + rc.senseNearbyTrees(info.bodyRadius * 2, rc.getTeam).length)
					} else {
						System.out.println("Tree location became blocked in direction " + dir)
						// Ignore building a tree there
					}
				}

				System.out.println("Planted all trees")

				while(rc.senseNearbyTrees(info.bodyRadius*2, rc.getTeam).length > 0) {
					water()
					yieldAndDoBackgroundTasks()
				}

				System.out.println("Lost all trees around me, moving again")
			}

			if (rc.canMove(target)) {
				rc.move(target)
				moveFailCounter = 0
			} else {
				// Couldn't move there? huh
				moveFailCounter += 1
				//System.out.println("Move failed")
			}

			/*
			// Randomly attempt to build a soldier or lumberjack in this direction
			if (rc.canBuildRobot(RobotType.SOLDIER, dir) && Math.random < .01) {
				rc.buildRobot(RobotType.SOLDIER, dir)
			} else if (rc.canBuildRobot(RobotType.LUMBERJACK, dir) && Math.random < .01 && rc.isBuildReady) {
				rc.buildRobot(RobotType.LUMBERJACK, dir)
			}
			// Move randomly
			tryMove(randomDirection)
			*/
			// Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
			water()
			yieldAndDoBackgroundTasks()
		}
	}
}
