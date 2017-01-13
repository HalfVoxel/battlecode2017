package bot

import battlecode.common._

import scala.util.Random

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

		if (canSeeTarget && (!rc.onTheMap(target.add(0, 0.001f), info.bodyRadius + GameConstants.BULLET_TREE_RADIUS) || rc.isCircleOccupiedExceptByThisRobot(target.add(0, 0.001f), freeRadius))) {
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
			target = rc.getLocation.add(dir, info.strideRadius * 3)

			if (Random.nextFloat() < 0.5) {
				// Ensure it is far away from the spawn pos
				target = spawnPos.add(spawnPos.directionTo(target), Math.max(spawnPos.distanceTo(target), info.bodyRadius * 8))
			}

			tests += 1
		} while (tests < 10 && !likelyValidTarget(target, freeRadius))

		target
	}

	def buildLumberjackInDenseForests(): Unit = {
		if (!rc.hasRobotBuildRequirements(RobotType.LUMBERJACK)) return
		//if (spawnedCount(RobotType.LUMBERJACK) >= 2) return

		val trees = rc.senseNearbyTrees(info.sensorRadius, Team.NEUTRAL)
		var totalScore = 0f
		for (tree <- trees) {
			// Add a small constant to make it favorable to just chop down trees for space
			totalScore += treeScore(tree) + 0.1f
		}

		// Very approximate
		val turnsToBreakEven = RobotType.LUMBERJACK.bulletCost / (totalScore + 0.001)

		//System.out.println("Score " + totalScore + " turns to break even " + turnsToBreakEven)
		val modifier = (1 + rc.getTeamBullets*0.001f) / (1f + spawnedCount(RobotType.LUMBERJACK))
		if (turnsToBreakEven < 100 * modifier) {
			// Create a woodcutter
			for (i <- 0 to 6) {
				val dir: Direction = randomDirection
				if (rc.canBuildRobot(RobotType.LUMBERJACK, dir)) {
					rc.buildRobot(RobotType.LUMBERJACK, dir)
					rc.broadcast(RobotType.LUMBERJACK.ordinal(), spawnedCount(RobotType.LUMBERJACK) + 1)
					return
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
		var hasSettled = false
		var unsettledTime = 0
		val STOP_SPENDING_AT_TIME = 100

		buildLumberjackInDenseForests()

		while (true) {
			val turnsLeft = rc.getRoundLimit - rc.getRoundNum
			var saveForTank = false
			var tankCount = spawnedCount(RobotType.TANK)
			val gardenerCount = spawnedCount(RobotType.GARDENER)
			val scoutCount = spawnedCount(RobotType.SCOUT)
			if(rc.getTreeCount > tankCount*4+4 && rc.getTeamBullets <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1 && scoutCount > 2){
				saveForTank = true
			}

			if(!hasSettled) {
				unsettledTime += 1
				val trees = rc.senseNearbyTrees(info.sensorRadius, rc.getTeam)
				var minHealthTree: TreeInfo = null
				for (tree <- trees) {
					if ((minHealthTree == null || tree.health < minHealthTree.health) && tree.health < 30) {
						// This probably means the tree isn't tended to by anyone else
						minHealthTree = tree
					}
				}
				if (minHealthTree != null) {
					tryMove(rc.getLocation.directionTo(minHealthTree.location))
					target = rc.getLocation
				}
			}

			var invalidTarget = (moveFailCounter > 5 || !likelyValidTarget(target, desiredRadius)) && !hasSettled
			val canSeeTarget = target.distanceSquaredTo(rc.getLocation) < 0.01f || rc.canSenseAllOfCircle(target, desiredRadius)

			var dir = randomDirection
			if ((!hasBuiltScout || Math.sqrt(rc.getTreeCount+1) > scoutCount) && !saveForTank){
				saveForTank = true
				for (i <- 0 until 6) {
					val dir = new Direction(2 * Math.PI.toFloat * i / 6f)
					if (rc.canBuildRobot(RobotType.SCOUT, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
						rc.buildRobot(RobotType.SCOUT, dir)
						rc.broadcast(RobotType.SCOUT.ordinal(), scoutCount + 1)
						hasBuiltScout = true
					}
				}
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
			if(turnsLeft > STOP_SPENDING_AT_TIME)
				buildLumberjackInDenseForests()

			if (rc.hasRobotBuildRequirements(RobotType.TANK) && tankCount < 5) {
				for (i <- 0 until 6) {
					val dir = new Direction(2 * Math.PI.toFloat * i / 6f)
					if (rc.canBuildRobot(RobotType.TANK, dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
						rc.buildRobot(RobotType.TANK, dir)
						tankCount += 1
						rc.broadcast(RobotType.TANK.ordinal(), tankCount)
					}
				}
			}

			if (canSeeTarget && ((!invalidTarget && rc.getLocation.distanceSquaredTo(target) < 2f) || unsettledTime > 30)) {
				// At target

				for (i <- 0 until 6) {
					val dir = new Direction(2 * Math.PI.toFloat * i / 6f)

					if (rc.canPlantTree(dir) && !saveForTank && turnsLeft > STOP_SPENDING_AT_TIME) {
						hasSettled = true
						rc.plantTree(dir)
						System.out.println("Planted tree")
					} else {
						//System.out.println("Tree location became blocked in direction " + dir)
						// Ignore building a tree there
					}
				}

				//System.out.println("Lost all trees around me, moving again")
			}

			if(!hasSettled) {
				if (!rc.hasMoved && tryMove(target)) {
					moveFailCounter = 0
				} else {
					// Couldn't move there? huh
					moveFailCounter += 1
					//System.out.println("Move failed")
				}
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
