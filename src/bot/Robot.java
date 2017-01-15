package bot;

import battlecode.common.*;

abstract class Robot {
    RobotController rc = null;
    RobotType info = null;
    MapLocation spawnPos = null;

    int MAP_EDGE_BROADCAST_OFFSET = 10;
    int HIGH_PRIORITY_TARGET_OFFSET = 20;
    int GARDENER_OFFSET = 25;

    protected int EXPLORATION_OFFSET = 100;
    protected int EXPLORATION_CHUNK_SIZE = 25;

    protected int EXPLORATION_ORIGIN = EXPLORATION_OFFSET + 0;
    protected int EXPLORATION_EXPLORED = EXPLORATION_OFFSET + 2;
    protected int EXPLORATION_OUTSIDE_MAP = EXPLORATION_OFFSET + 4;

    int mapEdgesDetermined = 0;
    float[] mapEdges = new float[4];
    boolean countingAsAlive = true;


    public void init() {
        info = rc.getType();
        spawnPos = rc.getLocation();
    }

    abstract void run() throws GameActionException;

    /**
     * Returns a random Direction
     *
     * @return a random Direction
     */
    protected Direction randomDirection () {
        return new Direction((float)Math.random()* 2 * (float)Math.PI);
    }

    protected int spawnedCount (RobotType tp) throws GameActionException {
        return rc.readBroadcast(tp.ordinal());
    }

    boolean tryMove(MapLocation to) throws GameActionException {
        float dist = rc.getLocation().distanceTo(to);
        if (dist > 0) return tryMove(rc.getLocation().directionTo(to), 20, 3, dist);
        else return true;
    }

    /**
     * Attempts to move in a given direction, while avoiding small obstacles directly in the path.
     *
     * @param dir The intended direction of movement
     * @return true if a move was performed
     * @throws GameActionException
     */
    boolean tryMove(Direction dir) throws GameActionException {
        return tryMove(dir, 20, 3, info.strideRadius);
    }

    /**
     * Attempts to move in a given direction, while avoiding small obstacles direction in the path.
     *
     * @param dir           The intended direction of movement
     * @param degreeOffset  Spacing between checked directions (degrees)
     * @param checksPerSide Number of extra directions checked on each side, if intended direction was unavailable
     * @return true if a move was performed
     * @throws GameActionException
     */
    boolean tryMove(Direction dir, float degreeOffset, int checksPerSide, float distance) throws GameActionException {
        // First, try intended direction
        if (rc.canMove(dir, distance)) {
            rc.move(dir, distance);
            return true;
        }
        // Now try a bunch of similar angles
        boolean moved = false;
        int currentCheck = 1;
        while (currentCheck <= checksPerSide) {
            // Try the offset of the left side
            if (rc.canMove(dir.rotateLeftDegrees(degreeOffset * currentCheck), distance)) {
                rc.move(dir.rotateLeftDegrees(degreeOffset * currentCheck), distance);
                return true;
            }
            // Try the offset on the right side
            if (rc.canMove(dir.rotateRightDegrees(degreeOffset * currentCheck), distance)) {
                rc.move(dir.rotateRightDegrees(degreeOffset * currentCheck), distance);
                return true;
            }
            // No move performed, try slightly further
            currentCheck += 1;
        }

        // A move never happened, so return false.
        return false;
    }

    void yieldAndDoBackgroundTasks() throws GameActionException {
        determineMapSize();
        shakeNearbyTrees();
        updateLiveness();
        broadcastEnemyLocations();
        Clock.yield();
    }

    void broadcastExploration() throws GameActionException {
        // Determine chunk
        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN);
        MapLocation relativePos = rc.getLocation().translate(-origin.x, -origin.y);
        int cx = (int)Math.floor(relativePos.x / EXPLORATION_CHUNK_SIZE + 4);
        int cy = (int)Math.floor(relativePos.y / EXPLORATION_CHUNK_SIZE + 4);
        if (cx < 0 || cy < 0 || cx >= 8 || cy >= 8) {
            System.out.println("In chunk that is out of bounds! " + cx + " " + cy);
            return;
        }

        long exploredChunks = readBroadcastLong(EXPLORATION_EXPLORED);

        // Mark the chunk we are in as explored
        int chunkIndex = cy * 8 + cx;
        long newExploredChunks = exploredChunks | (1 << chunkIndex);
        if (exploredChunks != newExploredChunks) {
            broadcast(EXPLORATION_EXPLORED, newExploredChunks);
        }
    }

    MapLocation findBestUnexploredChunk() throws GameActionException {
        long exploredChunks = readBroadcastLong(EXPLORATION_EXPLORED);
        long chunksOutsideMap = readBroadcastLong(EXPLORATION_OUTSIDE_MAP);
        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN);

        while(true) {
            // Consider chunks that have not been explored yet and are not outside the map
            long chunksToConsider = ~exploredChunks & ~chunksOutsideMap;

            MapLocation bestChunk = null;
            int bestChunkIndex = -1;
            float bestScore = 0f;

            // Loop through all chunks
            for (int y = 0; y < 8; y++) {
                int indexy = y * 8;
                for (int x = 0; x < 8; x++) {
                    int index = indexy + x;
                    // Ignore chunks that we can never reach or that are already explored
                    if ((chunksToConsider & (1L << index)) != 0) {
                        // Chunk position
                        MapLocation chunkPosition = new MapLocation(origin.x + (x - 4 + 0.5f) * EXPLORATION_CHUNK_SIZE, origin.y + (y - 4 + 0.5f) * EXPLORATION_CHUNK_SIZE);
                        float score = 1f / chunkPosition.distanceTo(rc.getLocation());

						/*try {
							setIndicatorDot(chunkPosition, 10 * score)
						} catch {
							case e:Exception =>
						}*/

                        if (score > bestScore) {
                            bestScore = score;
                            bestChunk = chunkPosition;
                            bestChunkIndex = index;
                        }
                    }
                }
            }

            if (bestChunk != null) {
                // Check if the chunk is on the map using the currently known information
                if (onMap(bestChunk)) {
                    return bestChunk;
                } else {
                    chunksOutsideMap |= 1L << bestChunkIndex;
                    broadcast(EXPLORATION_OUTSIDE_MAP, chunksOutsideMap);
                }
            } else {
                // Reset exploration
                exploredChunks = 0L;
                broadcast(EXPLORATION_EXPLORED, exploredChunks);
            }
        }
    }

    /** True if the location is on the map using the information known so far */
    boolean onMap (MapLocation pos) {
        return !(
                ((mapEdgesDetermined & 1) == 0 || pos.x < mapEdges[0]) &&
                        ((mapEdgesDetermined & 2) == 0 || pos.y < mapEdges[1]) &&
                        ((mapEdgesDetermined & 4) == 0 || pos.x > mapEdges[2]) &&
                        ((mapEdgesDetermined & 8) == 0 || pos.y > mapEdges[3])
        );
    }

    void broadcastEnemyLocations() throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(info.sensorRadius, rc.getTeam().opponent());

        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(GARDENER_OFFSET);

        boolean anyHostiles = false;
        boolean anyGardeners = false;
        for (RobotInfo robot : robots) {
            if (robot.getType() != RobotType.ARCHON) {
                anyHostiles = true;
                anyGardeners |= robot.getType() == RobotType.GARDENER;

                if (robot.attackCount > 0) {
                    // Aaaaah! They are attacking!! High priority target
                    int previousTick = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);

                    // Keep the same target for at least 5 ticks
                    if (rc.getRoundNum() > previousTick + 5) {
                        rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, rc.getRoundNum());
                        broadcast(HIGH_PRIORITY_TARGET_OFFSET + 1, robot.location);
                    }
                }

                if (robot.getType() == RobotType.GARDENER) {
                    // Ha! Found a gardener, we can harass that one

                    // Keep the same target for at least 5 ticks
                    int previousTick = rc.readBroadcast(GARDENER_OFFSET);
                    if (rc.getRoundNum() > previousTick + 5) {
                        rc.broadcast(GARDENER_OFFSET, rc.getRoundNum());
                        broadcast(GARDENER_OFFSET + 1, robot.location);
                    }
                }
            }
        }

        // Clear gardener and high priority target if we can see those positions but we cannot see any hostile units
        if (!anyHostiles && lastAttackingEnemySpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET+1), info.sensorRadius*0.7f)) {
            rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        }

        if (!anyGardeners && lastGardenerSpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(GARDENER_OFFSET+1), info.sensorRadius*0.7f)) {
            rc.broadcast(GARDENER_OFFSET, -1000);
        }
    }

    void broadcast (int channel, MapLocation pos) throws GameActionException {
        rc.broadcast(channel, (int)(pos.x * 1000));
        rc.broadcast(channel + 1, (int)(pos.y * 1000));
    }

    void broadcast (int channel, long v) throws GameActionException {
        rc.broadcast(channel, (int)(v >> 32));
        rc.broadcast(channel + 1, (int)v);
    }

    long readBroadcastLong (int channel) throws GameActionException {
        return ((long)rc.readBroadcast(channel) << 32) | (long)rc.readBroadcast(channel+1);
    }

    MapLocation readBroadcastPosition (int channel) throws GameActionException {
        return new MapLocation(rc.readBroadcast(channel) / 1000f, rc.readBroadcast(channel+1) / 1000f);
    }

    void updateLiveness () throws GameActionException {
        float countAsDeadLimit = 10;
        if (countingAsAlive && rc.getHealth() <= countAsDeadLimit) {
            rc.broadcast(info.ordinal(), spawnedCount(info) - 1);
            countingAsAlive = false;
        } else if (!countingAsAlive && rc.getHealth() > countAsDeadLimit) {
            rc.broadcast(info.ordinal(), spawnedCount(info) + 1);
            countingAsAlive = true;
        }
    }

    void setIndicatorDot (MapLocation pos, float value) throws GameActionException {
        float r = Math.max(Math.min(value * 3f, 1f), 0f);
        float g = Math.max(Math.min((value - 1/3f) * 3f, 1f), 0f);
        float b = Math.max(Math.min((value - 2/3f) * 3f, 1f), 0f);

        rc.setIndicatorDot(pos, (int)(r * 255f), (int)(g * 255f), (int)(b * 255f));
    }

    void shakeNearbyTrees () throws GameActionException {
        if (rc.canShake()) {
            TreeInfo[] trees = rc.senseNearbyTrees(info.bodyRadius + info.strideRadius);
            TreeInfo bestTree = null;
            for (TreeInfo tree : trees) {
                // Make sure it is not the tree of an opponent
                if (!tree.team.isPlayer() || tree.team == rc.getTeam()) {
                    // Make sure the tree has bullets and pick the tree with the most bullets in it
                    if (tree.containedBullets > 1 && (bestTree == null || tree.containedBullets > bestTree.containedBullets) && rc.canShake(tree.getID())) {
                        bestTree = tree;
                    }
                }
            }

            if (bestTree != null) {
                rc.shake(bestTree.getID());
            }
        }
    }

    void determineMapSize () throws GameActionException {
        // Abort if all map edges have already been determined
        if (mapEdgesDetermined == 0xF) return;

        int globalMapEdgesDetermined = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET);
        if (globalMapEdgesDetermined != mapEdgesDetermined) {
            mapEdgesDetermined = globalMapEdgesDetermined;
            for (int i = 0; i < 4; i++) {
                mapEdges[i] = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1) / 1000f;
            }
        }

        int tmpDetermined = mapEdgesDetermined;
        for (int i = 0; i < 4; i++) {
            if ((mapEdgesDetermined & (1 << i)) == 0) {
                float angle = i * (float)Math.PI / 4f;
                if (!rc.onTheMap(rc.getLocation().add(angle, info.sensorRadius * 0.99f))) {
                    // Found map edge
                    float mn = 0f;
                    float mx = info.sensorRadius * 0.99f;
                    while (mx - mn > 0.002f) {
                        float mid = (mn + mx) / 2;
                        if (rc.onTheMap(rc.getLocation().add(angle, mid))) {
                            mn = mid;
                        } else {
                            mx = mid;
                        }
                    }

                    MapLocation mapEdge = rc.getLocation().add(angle, mn);
                    float result = i % 2 == 0 ? mapEdge.x : mapEdge.y;
                    rc.broadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1, (int)(result * 1000));
                    // This robot will pick up the change for real the next time determineMapSize is called
                    tmpDetermined |= (1 << i);
                    rc.broadcast(MAP_EDGE_BROADCAST_OFFSET, tmpDetermined);
                    System.out.println("Found map edge " + i + " at " + result);
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
    boolean willCollideWithMe(BulletInfo bullet) {
        MapLocation myLocation = rc.getLocation();
        // get() relevant bullet information
        Direction propagationDirection = bullet.dir;
        MapLocation bulletLocation = bullet.location;
        // Calculate bullet relations to this robot
        Direction directionToRobot = bulletLocation.directionTo(myLocation);
        float distToRobot = bulletLocation.distanceTo(myLocation);
        float theta = propagationDirection.radiansBetween(directionToRobot);
        // If theta > 90 degrees, then the bullet is traveling away from us and we can break early
        if (Math.abs(theta) > Math.PI / 2) {
            return false;
        }
        // distToRobot is our hypotenuse, theta is our angle, and we want to know this length of the opposite leg.
        // This is the distance of a line that goes from myLocation and intersects perpendicularly with propagationDirection.
        // This corresponds to the smallest radius circle centered at our location that would intersect with the
        // line that is the path of the bullet.
        float perpendicularDist = (float)Math.abs(distToRobot * Math.sin(theta)); // soh cah toa :)
        return (perpendicularDist <= rc.getType().bodyRadius);
    }

    /** Return value in [bullets/tick] */
    float treeScore (TreeInfo tree, MapLocation fromPos) {
        float turnsToChopDown = 1f;
        turnsToChopDown += (tree.health/GameConstants.LUMBERJACK_CHOP_DAMAGE);
        if (fromPos != null) turnsToChopDown += Math.sqrt(fromPos.distanceTo(tree.location)/info.strideRadius);

        float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / turnsToChopDown;
        return score;
    }

    Team teamOf (BodyInfo b) {
        if (b != null && b.isRobot()) return ((RobotInfo)b).team;
        if (b != null && b.isTree()) return ((TreeInfo)b).team;
        return Team.NEUTRAL;
    }

    /** First body on the line segment going from the edge of this robot to the specified location.
     * Uses sampling so it is not perfectly accurate.
     */
    BodyInfo linecast (MapLocation b) throws GameActionException {
        MapLocation a = rc.getLocation();
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);
        dist = Math.min(dist, info.sensorRadius * 0.99f);

        float offset = Math.min(info.bodyRadius + 0.001f, dist);
        a = a.add(dir, offset);
        dist -= offset;

        if (dist <= 0) {
            return null;
        }

        int steps = (int)(dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float)steps);
            if (rc.isLocationOccupied(p)) {
                RobotInfo robot = rc.senseRobotAtLocation(p);
                if (robot != null && robot.ID != rc.getID()) return robot;

                TreeInfo tree = rc.senseTreeAtLocation(p);
                if (tree != null) return tree;
            }
        }

        return null;
    }

    BodyInfo linecastIgnoreTrees (MapLocation b) throws GameActionException {
        MapLocation a = rc.getLocation();
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);
        dist = Math.min(dist, info.sensorRadius * 0.99f);

        float offset = Math.min(info.bodyRadius + 0.001f, dist);
        a = a.add(dir, offset);
        dist -= offset;

        if (dist <= 0) {
            return null;
        }

        int steps = (int)(dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float)steps);
            if (rc.isLocationOccupiedByRobot(p)) {
                RobotInfo robot = rc.senseRobotAtLocation(p);
                if (robot != null && robot.ID != rc.getID()) return robot;
            }
        }

        return null;
    }

    static <T> T randomChoice(T[] values) {
        return values.length > 0 ? values[(int)(Math.random()*values.length)] : null;
    }
}
