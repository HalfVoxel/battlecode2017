package bot;

import battlecode.common.*;

import java.util.*;

abstract class Robot {
    static RobotController rc = null;

    /** rc.getType, should ideally be called 'type' but that is unfortunately a keyword */
    static RobotType type = null;
    MapLocation spawnPos = null;
    Random rnd = new Random(SEED);
    int lastAttackedEnemyID = -1;
    int roundAtStart = 0;

    static final int SEED = 0;
    static final int MAP_EDGE_BROADCAST_OFFSET = 10;
    static final int HIGH_PRIORITY_TARGET_OFFSET = 20;
    static final int GARDENER_OFFSET = 25;
    static final int TREE_OFFSET = 30;
    static final int GARDENER_CAN_PROBABLY_BUILD = 40;
    static final int PRIMARY_UNIT = 50;
    static final int TARGET_OFFSET = 60;
    static final int NUMBER_OF_TARGETS = 4;
    static final int HAS_SEEN = 1000;
    static final int PATHFINDING = 3000;
    static final int PATHFINDING_RESULT_TO_ENEMY_ARCHON = 4000;
    static final int PATHFINDING_TREE = 5000;
    static final int HIGH_PRIORITY = 6000;

    static final int[] dx = new int[]{1, 0, -1, 0};
    static final int[] dy = new int[]{0, 1, 0, -1};

    private static final int EXPLORATION_OFFSET = 100;
    static final float PATHFINDING_NODE_SIZE = 2.01f;
    static final int PATHFINDING_CHUNK_SIZE = 4;
    static final int PATHFINDING_WORLD_WIDTH = 100;
    private static final float PATHFINDING_CHUNK_RADIUS = PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE * 0.707106781f + 0.001f;

    static final int EXPLORATION_ORIGIN = EXPLORATION_OFFSET + 0;
    private static final int EXPLORATION_EXPLORED = EXPLORATION_OFFSET + 2;
    private static final int EXPLORATION_OUTSIDE_MAP = EXPLORATION_OFFSET + 4;

    static final int STOP_SPENDING_AT_TIME = 50;

    static int mapEdgesDetermined = 0;
    static float mapEdges0, mapEdges1, mapEdges2, mapEdges3;
    boolean countingAsAlive = true;
    private Map<Integer, Float> bulletHitDistance = new HashMap<>();
    private Map<Integer, MapLocation> unitLastLocation = new HashMap<>();

    static MapLocation explorationOrigin;

    void init() throws GameActionException {
        type = rc.getType();
        spawnPos = rc.getLocation();

        // Conservative map edges
        mapEdges0 = Math.min(spawnPos.x, 500f) + GameConstants.MAP_MAX_WIDTH;
        mapEdges1 = Math.min(spawnPos.y, 500f) + GameConstants.MAP_MAX_WIDTH;
        mapEdges2 = Math.max(spawnPos.x - GameConstants.MAP_MAX_WIDTH, 0f);
        mapEdges3 = Math.max(spawnPos.y - GameConstants.MAP_MAX_WIDTH, 0f);

        // Set the exploration origin if it has not been set already
        if (readBroadcastLong(EXPLORATION_ORIGIN) == 0L) {
            System.out.println("Set exploration origin");
            explorationOrigin = rc.getLocation().translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);
            broadcast(EXPLORATION_ORIGIN, explorationOrigin);

            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (0 + 1), mapEdges0);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (1 + 1), mapEdges1);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (2 + 1), mapEdges2);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (3 + 1), mapEdges3);
        } else {
            explorationOrigin = readBroadcastPosition(EXPLORATION_ORIGIN);
        }

        onStartOfTick();
    }

    abstract void run() throws GameActionException;

    /**
     * Returns a random Direction
     *
     * @return a random Direction
     */
    protected Direction randomDirection() {
        return new Direction(rnd.nextFloat() * 2 * (float)Math.PI);
    }

    protected int spawnedCount(RobotType tp) throws GameActionException {
        return rc.readBroadcast(tp.ordinal());
    }

    /**
     * Adds the ID to a global shared set of all detected entities and returns if this was
     * the first time that entity was detected.
     *
     * @param id ID of the entity, assumed to be in the range 0...32000.
     * @return True if this was the first time this particular ID was detected. False otherwise.
     * @throws GameActionException
     */
    boolean detect(int id) throws GameActionException {
        // Note that the right shift by id will only use the last 5 bits
        // and thus this will pick out the id'th bit in the broadcast array when starting
        // from the offset HAS_SEEN
        if (((rc.readBroadcast(HAS_SEEN + (id >> 5)) >> id) & 1) == 0) {
            // Broadcast that index but with the bit set
            rc.broadcast(HAS_SEEN + (id >> 5), rc.readBroadcast(HAS_SEEN + (id >> 5)) | (1 << id));
            return true;
        }
        return false;
    }

    /**
     * Adds the ID to a global shared set of all high priority entities
     *
     * @param id ID of the entity, assumed to be in the range 0...32000.
     * @throws GameActionException
     */
    void markAsHighPriority(int id) throws GameActionException {
        // Note that the right shift by id will only use the last 5 bits
        // and thus this will pick out the id'th bit in the broadcast array when starting
        // from the offset HAS_SEEN
        // Broadcast that index but with the bit set
        rc.broadcast(HIGH_PRIORITY + (id >> 5), rc.readBroadcast(HAS_SEEN + (id >> 5)) | (1 << id));
    }

    boolean isHighPriority (int id) throws GameActionException {
        return ((rc.readBroadcast(HIGH_PRIORITY + (id >> 5)) >> id) & 1) != 0;
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
        return tryMove(dir, 20, 3, type.strideRadius);
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

    void onStartOfTick() throws GameActionException {
        roundAtStart = rc.getRoundNum();

        if (rc.readBroadcast(PRIMARY_UNIT) != rc.getRoundNum()) {
            // We are the designated primary unit (first unit in spawn order, usually archons)
            rc.broadcast(PRIMARY_UNIT, rc.getRoundNum());

            considerDonating();
        }
    }

    void yieldAndDoBackgroundTasks() throws GameActionException {
        updateLiveness();
        if (Clock.getBytecodesLeft() > 1000) determineMapSize();
        if (Clock.getBytecodesLeft() > 1000 || rc.getType() == RobotType.GARDENER) shakeNearbyTrees();
        if (Clock.getBytecodesLeft() > 200) broadcastExploration();
        if (Clock.getBytecodesLeft() > 1000) broadcastEnemyLocations(null);

        if (rc.getRoundNum() != roundAtStart) {
            System.out.println("Error! Did not finish within the bytecode limit");
        }

        Clock.yield();
        onStartOfTick();
    }

    MapLocation directionToEnemyArchon(MapLocation loc) throws GameActionException {
        MapLocation relativePos = loc.translate(-explorationOrigin.x, -explorationOrigin.y);
        int nx = (int)Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int ny = (int)Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);

        if (nx < 0 || ny < 0 || nx >= PATHFINDING_WORLD_WIDTH || ny >= PATHFINDING_WORLD_WIDTH) {
            System.out.println("In chunk that is out of bounds! " + nx + " " + ny);
            return loc;
        }

        int cx = nx / 4;
        int cy = ny / 4;

        int chunkIndex = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        int dir = (rc.readBroadcast(PATHFINDING_RESULT_TO_ENEMY_ARCHON + chunkIndex) >> 2 * ((ny % 4) * PATHFINDING_CHUNK_SIZE + (nx % 4))) & 0x3;

        // Direction needs to be reversed to move toward the archon
        dir = (dir + 2) % 4;

        int tx = nx + dx[dir];
        int ty = ny + dy[dir];
        MapLocation target = explorationOrigin.translate((tx + 0.5f) * PATHFINDING_NODE_SIZE, (ty + 0.5f) * PATHFINDING_NODE_SIZE);
        return target;
    }

    static int pathfindingChunkDataForNode(int nodeX, int nodeY) throws GameActionException {
        int cx = nodeX / PATHFINDING_CHUNK_SIZE;
        int cy = nodeY / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        return rc.readBroadcast(PATHFINDING + index);
    }

    boolean isNodeReserved (int x, int y) throws GameActionException {
        int cx = x / PATHFINDING_CHUNK_SIZE;
        int cy = y / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        return ((rc.readBroadcast(PATHFINDING_TREE + index) >> ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE))) & 1) != 0;
    }

    void reserveNode (int x, int y) throws GameActionException {
        int cx = x / PATHFINDING_CHUNK_SIZE;
        int cy = y / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        int val = rc.readBroadcast(PATHFINDING_TREE + index);
        rc.setIndicatorDot(nodePosition(x, y), 0, 0, 255);
        rc.broadcast(PATHFINDING_TREE + index, val | 1 << ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE)));
    }

    /** Returns node index for the closest node */
    static int snapToNode (MapLocation loc) {
        MapLocation relativePos = loc.translate(-explorationOrigin.x, -explorationOrigin.y);
        int x = (int)Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int y = (int)Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        return y * PATHFINDING_WORLD_WIDTH + x;
    }

    /** Returns a bitpacked value where bit 0 indicates if the node is blocked and bit 1 indicates if the node chunk has been fully explored yet */
    static int nodeInfo (int x, int y) throws GameActionException {
        int chunk = pathfindingChunkDataForNode(x, y);
        int blocked = (chunk >> ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE))) & 1;
        int fullyExplored = (chunk >>> 30) & 0x2;
        return fullyExplored | blocked;
    }

    static MapLocation nodePosition (int x, int y) {
        return explorationOrigin.translate((x + 0.5f) * PATHFINDING_NODE_SIZE, (y + 0.5f) * PATHFINDING_NODE_SIZE);
    }

    void broadcastExploration() throws GameActionException {
        if (!doChunkJob()) return;

        // Determine chunk
        MapLocation relativePos = rc.getLocation().translate(-explorationOrigin.x, -explorationOrigin.y);
        int cx = (int)Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int cy = (int)Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        if (cx < 0 || cy < 0 || cx >= PATHFINDING_WORLD_WIDTH || cy >= PATHFINDING_WORLD_WIDTH) {
            System.out.println("In chunk that is out of bounds! " + cx + " " + cy);
            return;
        }

        cx /= PATHFINDING_CHUNK_SIZE;
        cy /= PATHFINDING_CHUNK_SIZE;

        //MapLocation chunkCenter0 = origin.translate((cx + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE, (cy + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE);

        int recalculationTime = (rc.getRoundNum() / 100) & 0xF;

        float totalWeight = 0f;

        for (int dy = -1; dy <= 1; dy++) {
            int ny = cy + dy;
            for (int dx = -1; dx <= 1; dx++) {
                if (Clock.getBytecodesLeft() < 800) break;

                int nx = cx + dx;
                int index = ny * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + nx;
                int chunkInfo = rc.readBroadcast(PATHFINDING + index);

                //MapLocation chunkCenter1 = origin.translate((nx + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE, (ny + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE);
                //rc.debug_setIndicatorDot(chunkCenter1, chunkInfo == 0 ? 128 : 0, 0, rc.canSenseAllOfCircle(chunkCenter1, chunkRadius) ? 255 : 0);

                boolean outdated = ((chunkInfo >> 21) & 0xF) != recalculationTime;
                //noinspection NumericOverflow
                if ((chunkInfo & (1 << 31)) == 0 || outdated) {
                    // Chunk is not explored yet

                    MapLocation chunkCenter = explorationOrigin.translate((nx + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE, (ny + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE);

                    if (rc.canSensePartOfCircle(chunkCenter, PATHFINDING_CHUNK_RADIUS)) {
                        float weight = 0.1f;
                        if (rc.canSenseAllOfCircle(chunkCenter, PATHFINDING_CHUNK_RADIUS)) weight += 2;
                        if (dx == 0 && dy == 0) weight += 0.2f;

                        // Reservoir sampling
                        totalWeight += weight;
                        if (rnd.nextFloat() * totalWeight < weight) {
                            // Start a new job
                            jobChunkCenter = chunkCenter;
                            jobChunkIndex = index;
                            jobChunkNx = nx;
                            jobChunkNy = ny;
                            jobChunkNodeIndex = 0;
                            // Clear the recalculation time for the chunk and update it with the current time
                            jobChunkInfo = (chunkInfo & (~(0xF << 21))) | recalculationTime << 21;
                            jobNodeSkips = 0;
                            jobChunkWasOutdated = outdated;
                        }
                    }
                }
            }
        }
    }

    private MapLocation jobChunkCenter;
    private int jobChunkIndex;
    private int jobChunkNx;
    private int jobChunkNy;
    private int jobChunkNodeIndex;
    private int jobChunkInfo;
    private int jobNodeSkips = 0;
    private boolean jobChunkWasOutdated;

    boolean doChunkJob() throws GameActionException {
        if (jobChunkCenter == null) return true;

        // Check if we can still see the whole chunk
        //if (!rc.canSenseAllOfCircle(jobChunkCenter, PATHFINDING_CHUNK_RADIUS)) return true;

        int startingTime = Clock.getBytecodeNum();

        boolean alreadyFullyExplored = (jobChunkInfo & (1 << 31)) != 0;

        // Loop through all the nodes in the chunk
        for (; jobChunkNodeIndex < PATHFINDING_CHUNK_SIZE * PATHFINDING_CHUNK_SIZE; jobChunkNodeIndex++) {
            if (Clock.getBytecodesLeft() < 500 || Clock.getBytecodeNum() - startingTime > 6000) return false;

            int prevValue = jobChunkInfo & (1 << jobChunkNodeIndex);
            if (prevValue != 0) {
                if (!jobChunkWasOutdated) {
                    // Seem we have already figured out that it is not traversable in an earlier update
                    // and it wasn't too long ago. Assume the world looks the same.
                    continue;
                }
            }

            int y = jobChunkNodeIndex / 4;
            int x = jobChunkNodeIndex % 4;

            boolean traversable = true;

            MapLocation nodeCenter = explorationOrigin.translate(((x + 0.5f) + jobChunkNx * PATHFINDING_CHUNK_SIZE) * PATHFINDING_NODE_SIZE, ((y + 0.5f) + jobChunkNy * PATHFINDING_CHUNK_SIZE) * PATHFINDING_NODE_SIZE);
            if (!onMap(nodeCenter, PATHFINDING_NODE_SIZE * 0.5f)) {
                traversable = false;
            } else {
                if (!rc.canSenseAllOfCircle(nodeCenter, PATHFINDING_NODE_SIZE)) {
                    // Have to skip this one
                    // If we have previously explored the whole chunk then we just want to update it
                    // with as much new information as we can, don't count skipped nodes because they
                    // will just contain the last information that was up to date
                    if (!alreadyFullyExplored) {
                        jobNodeSkips++;
                        //rc.setIndicatorDot(nodeCenter, 0, 40, 0);
                    }
                    continue;
                }

                if (rc.isCircleOccupiedExceptByThisRobot(nodeCenter, PATHFINDING_NODE_SIZE * 0.5f) && rc.senseNearbyTrees(nodeCenter, PATHFINDING_NODE_SIZE * 0.5f, null).length > 0) {
                    traversable = false;
                }
            }

            // Clear that bit
            jobChunkInfo &= ~(1 << jobChunkNodeIndex);

            if (traversable) {
                //rc.setIndicatorDot(nodeCenter, 40, 200, 10);
            } else {
                jobChunkInfo |= 1 << jobChunkNodeIndex;
                //rc.setIndicatorDot(nodeCenter, 200, 40, 10);
            }
        }

        int nodesCalculated = PATHFINDING_CHUNK_SIZE * PATHFINDING_CHUNK_SIZE - jobNodeSkips;
        int previousNodesCalculated = ((jobChunkInfo >> 16) & 0x1F);
        // Clear the previous value
        jobChunkInfo &= ~(0x1F << 16);
        jobChunkInfo |= nodesCalculated << 16;
        if (jobNodeSkips == 0) {
            // Mark as explored
            //noinspection NumericOverflow
            jobChunkInfo |= 1 << 31;
            rc.broadcast(PATHFINDING + jobChunkIndex, jobChunkInfo);
        } else {
            // If we have more or the same amount of information as the last time
            // this chunk was generated then update it, otherwise leave it be
            if (nodesCalculated >= previousNodesCalculated) {
                rc.broadcast(PATHFINDING + jobChunkIndex, jobChunkInfo);
            }
        }

        jobChunkCenter = null;
        return true;
    }

    MapLocation findBestUnexploredChunk() throws GameActionException {
        long exploredChunks = readBroadcastLong(EXPLORATION_EXPLORED);
        long chunksOutsideMap = readBroadcastLong(EXPLORATION_OUTSIDE_MAP);

        while (true) {
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
                        MapLocation chunkPosition = new MapLocation(explorationOrigin.x + (x - 4 + 0.5f) * PATHFINDING_NODE_SIZE, explorationOrigin.y + (y - 4 + 0.5f) * PATHFINDING_NODE_SIZE);
                        float score = 1f / chunkPosition.distanceTo(rc.getLocation());

						/*try {
                            debug_setIndicatorDot(chunkPosition, 10 * score)
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

    /**
     * True if the location is on the map using the information known so far
     */
    boolean onMap(MapLocation pos) {
        return pos.x <= mapEdges0 && pos.y <= mapEdges1 && pos.x >= mapEdges2 && pos.y >= mapEdges3;
    }

    /**
     * True if the location is on the map using the information known so far
     */
    boolean onMap(float x, float y) {
        return x <= mapEdges0 && y <= mapEdges1 && x >= mapEdges2 && y >= mapEdges3;
    }

    /**
     * True if the location is at least margin units from the edge of the map using the information known so far
     */
    boolean onMap(MapLocation pos, float margin) {
        return pos.x <= mapEdges0 - margin && pos.y <= mapEdges1 - margin && pos.x >= mapEdges2 + margin && pos.y >= mapEdges3 + margin;
    }

    boolean onMapX(float xcoord, float margin) {
        return xcoord <= mapEdges0 - margin && xcoord >= mapEdges2 + margin;
    }

    boolean onMapY(float ycoord, float margin) {
        return ycoord <= mapEdges1 - margin && ycoord >= mapEdges3 + margin;
    }

    /**
     * Clamp the location so that it lies on the map using the information known so far
     */
    MapLocation clampToMap(MapLocation pos) {
        return clampToMap(pos, 0);
    }

    /**
     * Clamp the location so that it lies on the map using the information known so far
     */
    MapLocation clampToMap(MapLocation pos, float margin) {
        float x = pos.x;
        float y = pos.y;
        x = Math.min(x, mapEdges0 - margin);
        y = Math.min(y, mapEdges1 - margin);
        x = Math.max(x, mapEdges2 + margin);
        y = Math.max(y, mapEdges3 + margin);
        return new MapLocation(x, y);
    }

    float getDistanceToMapEdge(MapLocation pos) {
        float ret = 10f;
        ret = Math.min(ret, mapEdges0 - pos.x);
        ret = Math.min(ret, mapEdges1 - pos.y);
        ret = Math.min(ret, pos.x - mapEdges2);
        ret = Math.min(ret, pos.y - mapEdges3);
        return ret;
    }

    void broadcastEnemyLocations(RobotInfo[] nearbyEnemies) throws GameActionException {
        Team enemy = rc.getTeam().opponent();

        if (nearbyEnemies == null) {
            nearbyEnemies = rc.senseNearbyRobots(type.sensorRadius, enemy);
        }

        int priority = 0;
        float friendlyMilitaryUnits = 0;
        float friendlyUnits = 0;
        float maxScore = 0;
        MapLocation maxScoreLocation = null;
        RobotInfo[] robots = rc.senseNearbyRobots();
        for(RobotInfo robot : robots){
            if(robot.getTeam() == enemy){
                float score = 0;
                switch(robot.type){
                    case ARCHON: score = 0; break;
                    case GARDENER: score = 150; break;
                    case LUMBERJACK: score = 30; break;
                    case SCOUT: score = 10; break;
                    case SOLDIER: score = 50; break;
                    case TANK: score = 140; break;
                    default: break;
                }
                if(score > maxScore){
                    maxScore = score;
                    maxScoreLocation = robot.location;
                }
                priority += score;
            }
            else{
                friendlyUnits += 1;
                if(robot.type != RobotType.ARCHON && robot.type != RobotType.GARDENER){
                    friendlyMilitaryUnits += 1;
                }
            }
        }
        priority /= Math.sqrt(friendlyMilitaryUnits + 2);
        if(maxScoreLocation != null) {
            int index = -1;
            boolean isBetter = true;
            for(int i = 0; i < NUMBER_OF_TARGETS; ++i){
                int offset = TARGET_OFFSET + 10*i;
                int timeSpotted = rc.readBroadcast(offset);
                int lastEventPriority = rc.readBroadcast(offset + 1);
                MapLocation loc = readBroadcastPosition(offset+2);
                if(lastEventPriority / (20 + rc.getRoundNum() - timeSpotted) < priority / 20 && loc.distanceTo(maxScoreLocation) < 20f) {
                    index = i;
                    rc.broadcast(offset + 1, 0);
                }
                if(lastEventPriority / (20 + rc.getRoundNum() - timeSpotted) >= priority / 20 && loc.distanceTo(maxScoreLocation) < 20f) {
                    isBetter = false;
                }
                if(lastEventPriority == 0){
                    index = i;
                }
            }
            if(isBetter && index != -1) {
                int offset = TARGET_OFFSET + 10*index;
                rc.broadcast(offset, rc.getRoundNum());
                rc.broadcast(offset + 1, priority);
                broadcast(offset + 2, maxScoreLocation);
            }
        }
        for(int i = 0; i < NUMBER_OF_TARGETS; ++i){
            int offset = TARGET_OFFSET + 10*i;
            int timeSpotted = rc.readBroadcast(offset);
            int lastEventPriority = rc.readBroadcast(offset + 1);
            MapLocation loc = readBroadcastPosition(offset+2);
            if(loc.distanceTo(rc.getLocation()) < 4f && 2*priority < lastEventPriority) {
                rc.broadcast(offset + 1, 2*priority);
            }
        }


        /*int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        int lastGardenerSpotted = rc.readBroadcast(GARDENER_OFFSET);

        boolean anyHostiles = false;
        boolean anyGardeners = false;
        for (RobotInfo robot : nearbyEnemies) {
            if (robot.getType() != RobotType.ARCHON && robot.team == enemy) {
                anyHostiles = true;
                anyGardeners |= robot.getType() == RobotType.GARDENER;

                if (robot.attackCount > 0) {
                    // Aaaaah! They are attacking!! High priority target
                    int previousTick = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);

                    // Keep the same target for at least 5 ticks
                    if (
                            (rc.getRoundNum() > previousTick + 5)) {
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
        if (!anyHostiles && lastAttackingEnemySpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1), type.sensorRadius * 0.7f)) {
            rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        }

        if (!anyGardeners && lastGardenerSpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(GARDENER_OFFSET + 1), type.sensorRadius * 0.7f)) {
            rc.broadcast(GARDENER_OFFSET, -1000);
        }*/
    }

    void broadcast(int channel, MapLocation pos) throws GameActionException {
        rc.broadcastFloat(channel, pos.x);
        rc.broadcastFloat(channel + 1, pos.y);
    }

    void broadcast(int channel, long v) throws GameActionException {
        rc.broadcast(channel, (int)(v >>> 32));
        rc.broadcast(channel + 1, (int)v);
    }

    long readBroadcastLong(int channel) throws GameActionException {
        return ((long)rc.readBroadcast(channel) << 32) | (long)rc.readBroadcast(channel + 1);
    }

    MapLocation readBroadcastPosition(int channel) throws GameActionException {
        return new MapLocation(rc.readBroadcastFloat(channel), rc.readBroadcastFloat(channel + 1));
    }

    void updateLiveness() throws GameActionException {
        float countAsDeadLimit = rc.getType() == RobotType.SCOUT ? 4 : 10;
        if (countingAsAlive && rc.getHealth() <= countAsDeadLimit) {
            rc.broadcast(type.ordinal(), spawnedCount(type) - 1);
            countingAsAlive = false;
        } else if (!countingAsAlive && rc.getHealth() > countAsDeadLimit) {
            rc.broadcast(type.ordinal(), spawnedCount(type) + 1);
            countingAsAlive = true;
        }
    }

    void debug_setIndicatorDot(MapLocation pos, float value) throws GameActionException {
        float r = Math.max(Math.min(value * 3f, 1f), 0f);
        float g = Math.max(Math.min((value - 1 / 3f) * 3f, 1f), 0f);
        float b = Math.max(Math.min((value - 2 / 3f) * 3f, 1f), 0f);

        rc.setIndicatorDot(pos, (int)(r * 255f), (int)(g * 255f), (int)(b * 255f));
    }

    void shakeNearbyTrees() throws GameActionException {
        if (rc.canShake()) {
            TreeInfo[] trees = rc.senseNearbyTrees(type.bodyRadius + GameConstants.INTERACTION_DIST_FROM_EDGE - 0.001f);
            TreeInfo bestTree = null;
            for (TreeInfo tree : trees) {
                // Make sure it is not the tree of an opponent
                if (tree.team == Team.NEUTRAL || tree.team == rc.getTeam()) {
                    // Make sure the tree has bullets and pick the tree with the most bullets in it
                    if (tree.containedBullets > 0 && (bestTree == null || tree.containedBullets > bestTree.containedBullets)) {
                        bestTree = tree;
                    }
                }
            }

            if (bestTree != null) {
                rc.shake(bestTree.getID());
            }
        }
    }

    void determineMapSize() throws GameActionException {
        // Abort if all map edges have already been determined
        if (mapEdgesDetermined == 0xF) return;

        int globalMapEdgesDetermined = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET);
        if (globalMapEdgesDetermined != mapEdgesDetermined) {
            mapEdgesDetermined = globalMapEdgesDetermined;
            mapEdges0 = rc.readBroadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (0 + 1));
            mapEdges1 = rc.readBroadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (1 + 1));
            mapEdges2 = rc.readBroadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (2 + 1));
            mapEdges3 = rc.readBroadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (3 + 1));
        }

        int tmpDetermined = mapEdgesDetermined;
        for (int i = 0; i < 4; i++) {
            if ((mapEdgesDetermined & (1 << i)) == 0) {
                float angle = i * (float)Math.PI / 2f;
                if (!rc.onTheMap(rc.getLocation().add(angle, type.sensorRadius * 0.99f))) {
                    // Found map edge
                    float mn = 0f;
                    float mx = type.sensorRadius * 0.99f;
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
                    rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + i + 1, result);
                    // This robot will pick up the change for real the next time determineMapSize is called
                    tmpDetermined |= (1 << i);
                    rc.broadcast(MAP_EDGE_BROADCAST_OFFSET, tmpDetermined);
                    System.out.println("Found map edge " + i + " at " + result);

                    // We also know that the other edge of the map is no further than MAX_WIDTH away from this edge
                    float otherEdge = i < 2 ? result - GameConstants.MAP_MAX_WIDTH : result + GameConstants.MAP_MAX_WIDTH;
                    int otherEdgeIndex = MAP_EDGE_BROADCAST_OFFSET + ((i + 2) % 4) + 1;

                    // Make sure we don't overwrite the edge with something worse
                    float prevValueForOtherEdge = rc.readBroadcastFloat(otherEdgeIndex);
                    if (i < 2) otherEdge = Math.max(otherEdge, prevValueForOtherEdge);
                    else otherEdge = Math.min(otherEdge, prevValueForOtherEdge);

                    rc.broadcastFloat(otherEdgeIndex, otherEdge);
                    System.out.println("Other map edge " + ((i + 2) % 4) + " must be around " + otherEdge);

                    rc.setIndicatorLine(mapEdge.add(angle + (float)Math.PI * 0.5f, 50), mapEdge.add(angle - (float)Math.PI * 0.5f, 50), 255, 255, 255);
                }
            }
        }
    }

    /**
     * Fire at any nearby robots if possible.
     *
     * @param hostileRobots
     * @return If there are any nearby gardeners (maybe move to different method?)
     * @throws GameActionException
     */
    void fireAtNearbyRobot(RobotInfo[] friendlyRobots, RobotInfo[] hostileRobots, boolean targetArchons) throws GameActionException {
        if (hostileRobots.length == 0) return;

        MapLocation myLocation = rc.getLocation();
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        List<Integer> bestRobotsTried = new ArrayList<>();
        for (int attemptN = 0; attemptN < 1; ++attemptN) {
            RobotInfo bestRobot = null;
            float bestScore2 = 0;
            MapLocation closestThreat = null;

            for (RobotInfo robot : hostileRobots) {
                if (bestRobotsTried.contains(robot.ID))
                    continue;
                MapLocation lastLoc = unitLastLocation.get(robot.getID());
                if(lastLoc == null){
                    lastLoc = robot.location;
                    unitLastLocation.put(robot.getID(), lastLoc);
                }
                if(!lastLoc.equals(robot.location) && lastLoc.x >= 0){
                    lastLoc = new MapLocation(-1f, -1f);
                    unitLastLocation.put(robot.getID(), lastLoc);
                }
                float score = 0;
                switch (robot.type) {
                    case GARDENER:
                        score += 100;
                        break;
                    case ARCHON:
                        score += targetArchons ? 1f : 0f;
                        break;
                    case SCOUT:
                        score += 50;
                        break;
                    case SOLDIER:
                        score += 150;
                        break;
                    default:
                        if (closestThreat == null || robot.location.distanceTo(myLocation) < closestThreat.distanceTo(myLocation)) {
                            closestThreat = robot.location;
                        }
                        score += 80;
                        break;
                }
                if(lastLoc.x >= 0) {
                    score *= 2;
                }
                score /= 4 + robot.health / robot.type.maxHealth;
                score /= myLocation.distanceTo(robot.location) + 1;
                if (score > bestScore2) {
                    bestScore2 = score;
                    bestRobot = robot;
                }
            }

            if (bestRobot != null) {
                if (bestRobot.getType() != RobotType.SCOUT && rc.getType() == RobotType.SCOUT) {
                    continue;
                }
                lastAttackedEnemyID = bestRobot.getID();
                bestRobotsTried.add(bestRobot.ID);

                BodyInfo firstUnitHit = linecast(bestRobot.location);
                if (rc.getLocation().distanceTo(bestRobot.location) < 2 * type.sensorRadius && teamOf(firstUnitHit) == rc.getTeam().opponent() && turnsLeft > STOP_SPENDING_AT_TIME) {
                    Direction dir = rc.getLocation().directionTo(bestRobot.location);
                    if (rc.canFirePentadShot() && rc.getTeamBullets() > 300 && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
                        rc.firePentadShot(dir);
                    }

                    if (rc.canFirePentadShot() && rc.getLocation().distanceTo(bestRobot.location) < 3.5f) {
                        rc.firePentadShot(dir);
                    }

                    if (rc.canFireTriadShot() && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
                        rc.fireTriadShot(dir);
                    }

                    if (rc.canFireTriadShot() && rc.getLocation().distanceTo(bestRobot.location) < 5.5f) {
                        rc.fireTriadShot(dir);
                    }

                    // And we have enough bullets, and haven't attacked yet this turn...
                    if (rc.canFireSingleShot()) {
                        // ...Then fire a bullet in the direction of the enemy.
                        rc.fireSingleShot(dir);
                    }

                    break;
                }
            } else {
                break;
            }
            if (Clock.getBytecodesLeft() < 3000)
                break;
        }
    }

    static final int SWEEP_DIRECTIONS = 30;
    float[] directionScores = new float[SWEEP_DIRECTIONS];
    float[] transparencies = new float[SWEEP_DIRECTIONS];

    void debug_directions() {
        for (int i = 0; i < SWEEP_DIRECTIONS; i++) {
            Direction d1 = new Direction(2 * (float)Math.PI * i / (float)SWEEP_DIRECTIONS - (float)Math.PI);
            Direction d2 = new Direction(2 * (float)Math.PI * (i + 1) / (float)SWEEP_DIRECTIONS - (float)Math.PI);
            float radius = rc.getType().bodyRadius * 1.5f;
            MapLocation p1 = rc.getLocation().add(d1, radius);
            MapLocation p2 = rc.getLocation().add(d2, radius);

            float value = directionScores[i];
            float r = Math.max(Math.min(value * 3f, 1f), 0f);
            float g = Math.max(Math.min((value - 1 / 3f) * 3f, 1f), 0f);
            float b = Math.max(Math.min((value - 2 / 3f) * 3f, 1f), 0f);

            rc.setIndicatorLine(p1, p2, (int)(r * 255f), (int)(g * 255f), (int)(b * 255f));
        }
    }

    void fireAtNearbyRobotSweep2(RobotInfo[] robots, TreeInfo[] trees) throws GameActionException {
        if (!rc.canFireSingleShot()) return;

        int numTrees = Math.min(trees.length, 4);
        int numRobots = Math.min(robots.length, 6);
        MapLocation myLocation = rc.getLocation();
        float bulletSpeed = rc.getType().bulletSpeed;
        Team enemy = rc.getTeam().opponent();

        int w1 = Clock.getBytecodeNum();

        for (int i = 0; i < directionScores.length; i++) {
            directionScores[i] = 0f;
            transparencies[i] = 1f;
        }

        // Go through the objects in sorted order
        float distanceToNextTree = numTrees > 0 ? myLocation.distanceTo(trees[0].location) - trees[0].radius * 0.2f : 10000;
        float distanceToNextRobot = numRobots > 0 ? myLocation.distanceTo(robots[0].location) : 10000;
        int ri = 0;
        int ti = 0;
        while (ri < numRobots || ti < numTrees) {
            float start;
            float end;
            float probabilityToHit;
            float pointsForHitting;

            if (distanceToNextTree < distanceToNextRobot) {
                // Closest thing is a tree
                TreeInfo tree = trees[ti];
                Direction dir = myLocation.directionTo(tree.location);
                float dist = distanceToNextTree;
                float halfwidth = tree.radius;
                float radiansDelta = Math.min(halfwidth / dist, (float)Math.PI);
                start = dir.radians - radiansDelta + (float)Math.PI;
                end = dir.radians + radiansDelta + (float)Math.PI;
                probabilityToHit = 1f;
                pointsForHitting = -0.001f;

                ti++;
                distanceToNextTree = ti < numTrees ? myLocation.distanceTo(trees[ti].location) - trees[0].radius * 0.2f : 10000;
            } else {
                // Closest thing is a robot
                RobotInfo robot = robots[ri];

                Direction dir = myLocation.directionTo(robot.location);
                float stride = 0.5f * robot.type.strideRadius;
                float dist = distanceToNextRobot;
                float halfwidth = robot.type.bodyRadius + stride * (dist / bulletSpeed);
                float radiansDelta = halfwidth / dist;

                start = dir.radians - radiansDelta + (float)Math.PI;
                end = dir.radians + radiansDelta + (float)Math.PI;

                probabilityToHit = robot.type.bodyRadius / halfwidth;
                if (robot.team == rc.getTeam()) {
                    pointsForHitting = -0.5f;
                } else {
                    switch (robot.type) {
                        case ARCHON:
                            pointsForHitting = rc.getRoundNum() > 2000 ? 0.8f : 0f;
                            break;
                        case GARDENER:
                            pointsForHitting = 0.5f;
                            break;
                        default:
                            pointsForHitting = 1f;
                            break;
                    }
                }

                ri++;
                distanceToNextRobot = ri < numRobots ? myLocation.distanceTo(robots[ri].location) : 10000;
            }

            int startIndex = (int)Math.floor(start * (SWEEP_DIRECTIONS / (2 * Math.PI)));
            int endIndex = (int)Math.floor(end * (SWEEP_DIRECTIONS / (2 * Math.PI)));

            pointsForHitting *= probabilityToHit;
            float probabilityOfNotHitting = 1f - probabilityToHit;

            if (startIndex < 0) {
                for (int i = startIndex + SWEEP_DIRECTIONS; i < SWEEP_DIRECTIONS; i++) {
                    directionScores[i] += pointsForHitting * transparencies[i];
                    transparencies[i] *= probabilityOfNotHitting;
                }

                startIndex = 0;
            }

            if (endIndex >= SWEEP_DIRECTIONS) {
                for (int i = endIndex - SWEEP_DIRECTIONS; i >= 0; i--) {
                    directionScores[i] += pointsForHitting * transparencies[i];
                    transparencies[i] *= probabilityOfNotHitting;
                }

                endIndex = SWEEP_DIRECTIONS - 1;
            }

            while (startIndex <= endIndex) {
                directionScores[startIndex] += pointsForHitting * transparencies[startIndex];
                transparencies[startIndex] *= probabilityOfNotHitting;
                startIndex++;
            }
        }

        Direction bestDirection = null;
        float bestScore = 0f;
        for (RobotInfo robot : robots) {
            if (robot.team == enemy) {
                Direction dir = myLocation.directionTo(robot.location);
                int index = (int)Math.floor((dir.radians + Math.PI) * (SWEEP_DIRECTIONS / (2 * Math.PI)));
                float score = directionScores[index];
                if (score > bestScore) {
                    bestScore = score;
                    bestDirection = dir;
                }
            }
        }

        if (bestDirection != null) {
            rc.fireSingleShot(bestDirection);
        }

        int w2 = Clock.getBytecodeNum();
        debug_directions();

        if (robots.length > 1) {
            System.out.println("SWEEP2: " + (w2 - w1) + " " + numRobots + " " + numTrees);
        }
    }

    final static int[] next = new int[100];
    final static float[] distances = new float[100];
    final static float[] probabilityOfNotHitting = new float[100];
    // Note: premultiplied with (1 - probabilityOfNotHitting)
    final static float[] pointsForHitting = new float[100];
    final static long[] events = new long[100];
    final static long[] potentialFiringDirections = new long[100];
    final static int[] initialEvents = new int[100];

    void fireAtNearbyRobotSweep(RobotInfo[] friendlyRobots, RobotInfo[] hostileRobots, TreeInfo[] trees) throws GameActionException {
        if (!rc.canFireSingleShot()) return;

        //String IDENT = "[" + rnd.nextInt(1000) + "]: ";

        int w1 = Clock.getBytecodeNum();
        // First 2 event indices are not used
        int eventCount = 2;
        MapLocation myLocation = rc.getLocation();
        float bulletSpeed = rc.getType().bulletSpeed;

        int initialEventCount = 0;
        int firingDirections = 0;

        int numTrees = Math.min(trees.length, 5);
        for (int i = 0; i < numTrees; i++) {
            TreeInfo tree = trees[i];

            Direction dir = myLocation.directionTo(tree.location);
            float dist = myLocation.distanceTo(tree.location);
            float halfwidth = tree.radius;
            float radiansDelta = halfwidth / dist;
            probabilityOfNotHitting[eventCount] = 1f;
            float start = dir.radians - radiansDelta + (float)Math.PI;
            float end = dir.radians + radiansDelta + (float)Math.PI;

            if (start < 0) {
                start += Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI * 2) {
                end -= Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long)(1000 * start) << 32 | eventCount;
            events[eventCount + 1] = (long)(1000 * end) << 32 | eventCount + 1;
            distances[eventCount] = dist;
            pointsForHitting[eventCount] = -0.01f;
            eventCount += 2;
        }

        int numFriendly = Math.min(friendlyRobots.length, 4);
        for (int i = 0; i < numFriendly; i++) {
            RobotInfo robot = friendlyRobots[i];

            Direction dir = myLocation.directionTo(robot.location);
            float stride = 0.5f * robot.type.strideRadius;
            float dist = myLocation.distanceTo(robot.location);
            float halfwidth = robot.type.bodyRadius + stride * (dist / bulletSpeed);
            float transparency = 1f - robot.type.bodyRadius / halfwidth;
            float radiansDelta = halfwidth / dist;
            //System.out.println("Width in radians: " + radiansDelta + " " + dist + " " + halfwidth);
            probabilityOfNotHitting[eventCount] = transparency;
            float start = dir.radians - radiansDelta + (float)Math.PI;
            float end = dir.radians + radiansDelta + (float)Math.PI;

            if (start < 0) {
                start += Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI * 2) {
                end -= Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long)(1000 * start) << 32 | eventCount;
            events[eventCount + 1] = (long)(1000 * end) << 32 | eventCount + 1;
            distances[eventCount] = dist;
            pointsForHitting[eventCount] = -1f;
            eventCount += 2;
        }

        int numHostile = Math.min(hostileRobots.length, 4);
        for (int i = 0; i < numHostile; i++) {
            RobotInfo robot = hostileRobots[i];

            Direction dir = myLocation.directionTo(robot.location);
            float stride = 0.5f * robot.type.strideRadius;
            float dist = myLocation.distanceTo(robot.location);
            float halfwidth = robot.type.bodyRadius + stride * (dist / bulletSpeed);
            float transparency = 1f - robot.type.bodyRadius / halfwidth;
            float radiansDelta = halfwidth / dist;
            //System.out.println("Width in radians: " + radiansDelta + " " + dist + " " + halfwidth);
            probabilityOfNotHitting[eventCount] = transparency;
            float start = dir.radians - radiansDelta + (float)Math.PI;
            float end = dir.radians + radiansDelta + (float)Math.PI;

            if (start < 0) {
                start += Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI * 2) {
                end -= Math.PI * 2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long)(1000 * start) << 32 | eventCount;
            events[eventCount + 1] = (long)(1000 * end) << 32 | eventCount + 1;
            distances[eventCount] = dist;
            pointsForHitting[eventCount] = 1f;
            eventCount += 2;

            potentialFiringDirections[firingDirections] = (long)(1000 * (dir.radians + (float)Math.PI));
            firingDirections++;
        }

        int w2 = Clock.getBytecodeNum();

        // Inlined sorting algorithm
        int left = 2;
        int right = eventCount - 1;
        for (int i = left, j = i; i < right; j = ++i) {
            long ai = events[i + 1];
            while (ai < events[j]) {
                events[j + 1] = events[j];
                if (j-- == left) {
                    break;
                }
            }
            events[j + 1] = ai;
        }

        int w3 = Clock.getBytecodeNum();

        int w4 = Clock.getBytecodeNum();
        Arrays.sort(potentialFiringDirections, 0, firingDirections);

        /*
        for (int i = 2; i < eventCount; i++) {
            long ev = events[i];
            int id = (int)ev;
            float angle = (ev >> 32) / 1000f;
            //System.out.println(IDENT + "Event " + id + " at angle " + angle);
        }*/

        // Add events crossing the 0 radian mark
        int prev = 0;
        for (int i = 0; i < initialEventCount; i++) {
            //System.out.println(IDENT + "Inserting initial id " + initialEvents[i]);
            prev = next[prev] = initialEvents[i];
        }
        next[prev] = -1;

        float bestScore = 0f;
        long bestAngle = -1;

        if (firingDirections == 0) {
            // Nowhere to shoot
            return;
        }

        int nextFiringDirectionIndex = 0;
        long nextFiringDirection = potentialFiringDirections[0];

        for (int i = 2; i < eventCount; i++) {
            long ev = events[i];
            int id = (int)ev;

            long angle = ev >> 32;
            if (angle > nextFiringDirection) {
                // Calculate score for interval
                float rayStrength = 1f;
                float score = 0f;
                int c = next[0];
                while (c != -1) {
                    score += rayStrength * pointsForHitting[c];
                    rayStrength *= probabilityOfNotHitting[c];
                    c = next[c];
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestAngle = nextFiringDirection;
                }

                nextFiringDirectionIndex++;
                if (nextFiringDirectionIndex < firingDirections) {
                    nextFiringDirection = potentialFiringDirections[nextFiringDirectionIndex];
                } else {
                    // No more firing directions to consider
                    break;
                }
            }

            if ((id & 1) == 0) {
                float dist = distances[id];
                // Start of interval
                // Insert into sorted linked list
                //System.out.println(IDENT + "Inserting id " + id);
                int c = 0;
                while (true) {
                    int k = next[c];
                    if (k == -1 || dist < distances[k]) {
                        next[id] = next[c];
                        next[c] = id;
                        break;
                    }
                    c = k;
                }
            } else {
                // End of interval
                // Remove from linked list
                //System.out.println(IDENT + "Removing id " + id);
                id -= 1;
                int c = 0;
                while (next[c] != id) {
                    c = next[c];
                }
                next[c] = next[next[c]];
            }
        }


        int w5 = Clock.getBytecodeNum();

        if (hostileRobots.length > 1) {
            System.out.println("Sweep: " + (w2 - w1) + " " + (w3 - w2) + " " + (w4 - w3) + " " + (w5 - w4) + ": " + hostileRobots.length + " " + friendlyRobots.length + " " + numTrees);
        }

        if (bestAngle != -1) {
            float angle = (bestAngle / 1000f) - (float)Math.PI;
            rc.fireSingleShot(new Direction(angle));
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

    /**
     * Return value in [bullets/tick]
     */
    float treeScore(TreeInfo tree, MapLocation fromPos) {
        float turnsToChopDown = 1f;
        turnsToChopDown += (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE);
        if (fromPos != null) turnsToChopDown += Math.sqrt(fromPos.distanceTo(tree.location) / type.strideRadius);

        float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + 1) / turnsToChopDown;
        return score;
    }

    Team teamOf(BodyInfo b) {
        if (b != null && b.isRobot()) return ((RobotInfo)b).team;
        if (b != null && b.isTree()) return ((TreeInfo)b).team;
        return Team.NEUTRAL;
    }

    /**
     * First body on the line segment.
     * Uses sampling so it is not perfectly accurate.
     * \warning Assumes both the start point and the end point are within the sensor radius
     */
    BodyInfo linecastUnsafe(MapLocation a, MapLocation b) throws GameActionException {
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);

        int steps = (int)(dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float)steps);
            if (rc.canSenseLocation(p) && rc.isLocationOccupied(p)) {
                RobotInfo robot = rc.senseRobotAtLocation(p);
                if (robot != null && robot.ID != rc.getID()) return robot;

                TreeInfo tree = rc.senseTreeAtLocation(p);
                if (tree != null) return tree;
            }
        }

        return null;
    }

    /**
     * Distance to first tree.
     * Uses sampling so it is not perfectly accurate.
     * <p>
     * Returns a very large number if no tree was hit.
     */
    float raycastForTree(MapLocation a, Direction dir) throws GameActionException {
        int t = 1;
        boolean hasBeenInside = false;
        while (true) {
            float dist = t * 2f;
            MapLocation p = a.add(dir, dist);
            if (rc.canSenseLocation(p)) {
                if (rc.isLocationOccupiedByTree(p)) {
                    if (rc.isLocationOccupiedByTree(a.add(dir, dist - 1.5f))) return dist - 1.5f;
                    if (rc.isLocationOccupiedByTree(a.add(dir, dist - 1.0f))) return dist - 1.0f;
                    if (rc.isLocationOccupiedByTree(a.add(dir, dist - 0.5f))) return dist - 0.5f;
                    return dist;
                }
                hasBeenInside = true;
            } else if (hasBeenInside) {
                return 1000;
            }
            t++;
        }
    }

    /**
     * First body on the line segment going from the edge of this robot to the specified location.
     * Uses sampling so it is not perfectly accurate.
     */
    BodyInfo linecast(MapLocation b) throws GameActionException {
        MapLocation a = rc.getLocation();
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);
        dist = Math.min(dist, type.sensorRadius * 0.99f);

        float offset = Math.min(type.bodyRadius + 0.001f, dist);
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

    BodyInfo linecastIgnoreTrees(MapLocation b) throws GameActionException {
        MapLocation a = rc.getLocation();
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);
        dist = Math.min(dist, type.sensorRadius * 0.99f);

        float offset = Math.min(type.bodyRadius + 0.001f, dist);
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

    int fallbackMovementDirection = 1;
    float fallbackDirectionLimit = 0.5f;
    int movementBlockedTicks = 0;
    MapLocation previousBestMove;

    float[] bulletX = new float[100];
    float[] bulletY = new float[100];
    float[] bulletDx = new float[100];
    float[] bulletDy = new float[100];
    float[] bulletDamage = new float[100];
    float[] bulletSpeed = new float[100];
    MapLocation[] movesToConsider = new MapLocation[100];

    void moveToAvoidBullets(MapLocation secondaryTarget, BulletInfo[] bullets, RobotInfo[] units) throws GameActionException {
        if (rc.hasMoved()) return;

        MapLocation reservedNodeLocation = null;
        if (type != RobotType.GARDENER) {
            // Test a random node inside the body radius
            MapLocation rndPos = rc.getLocation().add(rnd.nextFloat() * (float)Math.PI * 2, rnd.nextFloat() * (type.bodyRadius + PATHFINDING_NODE_SIZE * 0.5f));

            int index = snapToNode(rndPos);
            int x = index % PATHFINDING_WORLD_WIDTH;
            int y = index / PATHFINDING_WORLD_WIDTH;

            if (isNodeReserved(x, y)) {
                reservedNodeLocation = nodePosition(x, y);
            }
        }

        if (bullets.length == 0 && type != RobotType.LUMBERJACK && type != RobotType.ARCHON && reservedNodeLocation == null) {
            Direction desiredDir = rc.getLocation().directionTo(secondaryTarget);
            float desiredStride = Math.min(rc.getLocation().distanceTo(secondaryTarget), type.strideRadius);

            // Already at target
            if (desiredDir == null) return;

            final int steps = 12;
            float radiansPerStep = fallbackMovementDirection * fallbackDirectionLimit * (float)Math.PI / (float)steps;
            for (int i = 0; i < steps; i++) {
                float angle = i * radiansPerStep;
                Direction dir = desiredDir.rotateLeftRads(angle);
                //rc.setIndicatorLine(rc.getLocation(), rc.getLocation().add(dir, type.strideRadius), 200, fallbackMovementDirection > 0 ? 255 : 0, 200);
                if (rc.canMove(dir, desiredStride)) {
                    if (angle <= Math.PI*0.5f + 0.001f) {
                        fallbackDirectionLimit = 0.5f;
                    }
                    rc.move(dir, desiredStride);
                    movementBlockedTicks = 0;
                    break;
                }
            }

            if (!rc.hasMoved()) {
                // Failed to move while the limit was 0.5, then increase it to 1.0
                // and move in the other direction.
                if (fallbackDirectionLimit == 0.5f) {
                    fallbackDirectionLimit = 1f;
                    fallbackMovementDirection *= -1;
                    movementBlockedTicks = 0;
                    moveToAvoidBullets(secondaryTarget, bullets, units);
                } else {
                    movementBlockedTicks += 1;
                    if (movementBlockedTicks > 6) {
                        fallbackMovementDirection *= -1;
                        movementBlockedTicks = 0;
                        moveToAvoidBullets(secondaryTarget, bullets, units);
                    }
                }
            }

            return;
        }

        MapLocation myLocation = rc.getLocation();
        int numMovesToConsider = 0;

        if (secondaryTarget != null && myLocation.distanceTo(secondaryTarget) > 0) {
            movesToConsider[numMovesToConsider++] = myLocation.add(myLocation.directionTo(secondaryTarget), Math.min(myLocation.distanceTo(secondaryTarget), type.strideRadius));
            if(rc.getType() == RobotType.LUMBERJACK) {
                movesToConsider[numMovesToConsider++] = myLocation;
            }
        } else {
            movesToConsider[numMovesToConsider++] = myLocation;
        }

        if (units.length > 0) {
            Direction dir = myLocation.directionTo(units[0].location);
            movesToConsider[numMovesToConsider++] = myLocation.add(dir.opposite(), type.strideRadius);
        }

        if (previousBestMove != null && Math.hypot(previousBestMove.x, previousBestMove.y) > 0.01f) {
            MapLocation move = previousBestMove.translate(rc.getLocation().x, rc.getLocation().y);
            movesToConsider[numMovesToConsider++] = move;
            rc.setIndicatorLine(rc.getLocation(), move, 255, 0, 0);
        }

        int bulletsToConsider = Math.min(bullets.length, bulletX.length);
        // Discard bullets that cannot hit us and move the bullets that can hit us to the front of the array
        // The first bulletsToConsider bullets will can potentially hit us after the loop is done
        for (int i = 0; i < bulletsToConsider; i++) {
            if (!bulletCanHitUs(myLocation, bullets[i])) {
                bulletsToConsider--;
                bullets[i] = bullets[bulletsToConsider];
                i--;
            } else {
                BulletInfo bullet = bullets[i];
                bulletX[i] = bullet.location.x;
                bulletY[i] = bullet.location.y;
                bulletDx[i] = bullet.dir.getDeltaX(1);
                bulletDy[i] = bullet.dir.getDeltaY(1);
                bulletDamage[i] = bullet.damage;
                bulletSpeed[i] = bullet.speed;
            }
        }

        float bestScore = -1000000f;
        MapLocation bestMove = null;
        int iterationsDone = 0;
        // Save some processing power if we have no bullets to consider (will be used by e.g the exploration code)
        int maxIterations = bullets.length == 0 ? 5 : 1000;
        while ((Clock.getBytecodesLeft() > 3000 && iterationsDone < maxIterations) || iterationsDone < 2) {
            MapLocation loc;
            if (iterationsDone < numMovesToConsider) {
                loc = movesToConsider[iterationsDone];
            } else {
                Direction dir = randomDirection();
                float r = rnd.nextFloat();
                if (r < 0.5f)
                    loc = myLocation.add(dir, type.strideRadius);
                else if (r < 0.7f)
                    loc = myLocation.add(dir, type.strideRadius * 0.5f);
                else
                    loc = myLocation.add(dir, 0.2f);
            }

            iterationsDone += 1;
            if (rc.canMove(loc)) {
                float score = getDefensiveBulletAvoidanceScore(loc, reservedNodeLocation, bulletsToConsider, bulletX, bulletY, bulletDx, bulletDy,
                        bulletDamage, bulletSpeed, units, secondaryTarget);
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = loc;
                }
            }
        }

        // We need to check again that the move is legal, in case we exceeded the byte code limit
        if (bestMove != null && rc.canMove(bestMove)) {
            previousBestMove = bestMove.translate(-rc.getLocation().x, -rc.getLocation().y);
            rc.move(bestMove);
        }
    }

    float getDefensiveBulletAvoidanceScore(MapLocation loc, MapLocation reservedNodeLoc, int numBullets,
                                           float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                           float[] bulletDamage, float[] bulletSpeed,
                                           RobotInfo[] units, MapLocation target) throws GameActionException {
        Team myTeam = rc.getTeam();

        float score = 0f;
        boolean ignoreTarget = false;

        if (type == RobotType.ARCHON) {
            TreeInfo[] trees = rc.senseNearbyTrees(type.sensorRadius);
            for (TreeInfo tree : trees) {
                if (tree.getTeam() == Team.NEUTRAL) {
                    if (tree.containedBullets > 0) {
                        ignoreTarget = true;
                        score += Math.sqrt(tree.containedBullets) * Math.exp(-loc.distanceTo(tree.location) * 0.5);
                    } else {
                        score -= 2 * Math.exp(-loc.distanceTo(tree.location) * 0.5);
                    }
                } else if (tree.getTeam() == myTeam) {
                    score -= 10 * Math.exp(-loc.distanceTo(tree.location) * 0.5);
                }
            }

            // Avoid the edge of the map (easy for units to get stuck if the archon blocks them)
            float distx = 10f;
            float disty = 10f;
            distx = Math.min(distx, mapEdges0 - loc.x);
            disty = Math.min(disty, mapEdges1 - loc.y);
            distx = Math.min(distx, loc.x - mapEdges2);
            disty = Math.min(disty, loc.y - mapEdges3);
            score += (distx + disty) * 5f;
        }

        // Move away from reserved nodes if there are no enemies or bullets nearby
        if (reservedNodeLoc != null && bulletX.length == 0 && type != RobotType.GARDENER) {
            score += 2f * loc.distanceTo(reservedNodeLoc);
        }

        if (!ignoreTarget && target != null) {
            score -= 1.15f * loc.distanceTo(target);
        }

        if (type == RobotType.LUMBERJACK) {
            TreeInfo[] trees = rc.senseNearbyTrees();
            for (TreeInfo tree : trees) {
                if (tree.team == Team.NEUTRAL) {
                    score += Math.sqrt((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + tree.containedBullets + 1) / (loc.distanceTo(tree.location) + 1);
                } else if (tree.team == myTeam) {
                    score -= 1f / (loc.distanceTo(tree.location));
                } else{
                    score += 4f / (loc.distanceTo(tree.location));
                }
            }
            for (RobotInfo unit : units) {
                float dis = loc.distanceTo(unit.location);
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    score -= 2f / (dis + 1);
                    if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 1f + unit.type.bodyRadius) {
                        score -= 100;
                    }
                } else {
                    if (unit.type != RobotType.LUMBERJACK) {
                        //System.out.println("Applied bonus: " + (400000f / (dis + 1)));
                        score += 10000f / (dis + 1);
                        if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS) {
                            score += 10000;
                        }
                    }
                }
            }
        } else {
            for (RobotInfo unit : units) {
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    if (unit.type == RobotType.ARCHON || unit.type == RobotType.TANK)
                        score -= 1 / (unit.location.distanceTo(loc) + 1);
                    else
                        score -= 0.5 / (unit.location.distanceTo(loc) + 1);
                } else {
                    switch (unit.type) {
                        case SCOUT:
                        case SOLDIER:
                        case TANK:
                            score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                            break;
                        case LUMBERJACK:
                            float dis = loc.distanceTo(unit.location);
                            score -= 10f / (dis * dis + 1);
                            score += 0.8f / (dis + 1);
                            if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f) {
                                score -= 1000;
                            }
                            break;
                    }
                }
            }
        }

        score -= 1000f * getEstimatedDamageAtPosition(loc.x, loc.y, numBullets, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, null);

        return score;
    }

    /**
     * Determine whether a bullet can possibly hit us
     */
    boolean bulletCanHitUs(MapLocation loc, BulletInfo bullet) {
        float dmg = 0f;
        float radius = type.bodyRadius + type.strideRadius;
        float sqrRadius = radius * radius;
        // Current bullet position
        float prevX = bullet.location.x - loc.x;
        float prevY = bullet.location.y - loc.y;
        float dx = bullet.dir.getDeltaX(1);
        float dy = bullet.dir.getDeltaY(1);

        // Distance the bullet has to travel to get to its closest point to #loc
        float dot = -(dx * prevX + dy * prevY);
        // Position of the closest point the bullet will be to #loc
        float closestX = prevX + dx * dot;
        float closestY = prevY + dy * dot;
        float sqrDistanceToLineOfTravel = closestX * closestX + closestY * closestY;

        // The bullet cannot possibly hit us
        if (sqrDistanceToLineOfTravel > sqrRadius)
            return false;
        if (dot < 0 && sqrRadius - sqrDistanceToLineOfTravel < dot * dot) {
            // The bullet has already passed us. Everything is ok!
            return false;
        }

        return true;
    }

    /**
     * Estimated damage from bullets when moving to the specified position
     */
    float getEstimatedDamageAtPosition(float locx, float locy, int numBullets, float[] bulletX, float[] bulletY
            , float[] bulletDx, float[] bulletDy, float[] bulletDamage, float[] bulletSpeed, float[] bulletImpactDistances) {
        float dmg = 0f;
        float radius = type.bodyRadius;
        float sqrRadius = radius * radius;

        for (int i = 0; i < numBullets; i++) {

            // Current bullet position
            float prevX = bulletX[i] - locx;
            float prevY = bulletY[i] - locy;
            float dx = bulletDx[i];
            float dy = bulletDy[i];

            // Distance the bullet has to travel to get to its closest point to #loc
            float dot = -(dx * prevX + dy * prevY);
            // Position of the closest point the bullet will be to #loc
            float closestX = prevX + dx * dot;
            float closestY = prevY + dy * dot;
            float sqrDistanceToLineOfTravel = closestX * closestX + closestY * closestY;

            // The bullet cannot possibly hit us
            if (sqrDistanceToLineOfTravel > sqrRadius) continue;

            if (dot < 0 && sqrRadius - sqrDistanceToLineOfTravel < dot * dot) {
                // The bullet has already passed us. Everything is ok!
                continue;
            }
            float intersectionDistDelta = (float)Math.sqrt(sqrRadius - sqrDistanceToLineOfTravel);

            float intersectionDist1 = dot - intersectionDistDelta;

            if (bulletImpactDistances != null && intersectionDist1 > bulletImpactDistances[i]) {
                // The bullet has already hit a tree or something
                continue;
            }

            // -1 because the bullet has not moved this frame yet
            float timeToIntersection = (intersectionDist1 / bulletSpeed[i]) - 1;
            // It will hit us this frame
            if (timeToIntersection <= 0) {
                dmg += bulletDamage[i];
            } else {
                // Decrease the damage further away
                dmg += 0.5f * bulletDamage[i] / (timeToIntersection + 1);
            }
        }
        return dmg;
    }

    float[] updateBulletHitDistances(BulletInfo[] nearbyBullets) throws GameActionException {
        float[] bulletImpactDistances = new float[nearbyBullets.length];
        int w1 = Clock.getBytecodeNum();
        for (int i = 0; i < nearbyBullets.length; i++) {
            if (Clock.getBytecodeNum() - w1 > 500) {
                // Fill in the rest if the calculations have been done previously
                for (int j = i; j < nearbyBullets.length; j++) {
                    bulletImpactDistances[j] = bulletHitDistance.getOrDefault(nearbyBullets[j], 1000f);
                }
                break;
            } else {
                bulletImpactDistances[i] = bulletImpactDistance(nearbyBullets[i]);
            }
        }
        return bulletImpactDistances;
    }

    float bulletImpactDistance(BulletInfo bullet) throws GameActionException {
        if (!bulletHitDistance.containsKey(bullet.getID())) {
            float v = raycastForTree(bullet.location, bullet.dir);
            bulletHitDistance.put(bullet.getID(), v);
            return v;
        } else {
            return bulletHitDistance.get(bullet.getID());
        }
    }

    <T> T randomChoice(T[] values) {
        return values.length > 0 ? values[(int)(rnd.nextFloat() * values.length)] : null;
    }

    void considerDonating() throws GameActionException {
        double cost = rc.getVictoryPointCost();
        double income = rc.getTreeCount() + 2;
        double victoryPointsLeft = GameConstants.VICTORY_POINTS_TO_WIN - rc.getTeamVictoryPoints();
        double turnsUntilVictory = (victoryPointsLeft * cost) / income;
        int gardenerCount = spawnedCount(RobotType.GARDENER);
        int archonCount = spawnedCount(RobotType.ARCHON);
        if (turnsUntilVictory < 250 || rc.getRoundNum() > rc.getRoundLimit() - 300 || rc.getTeamBullets() > 2000 ||
                (gardenerCount == 0 && archonCount == 0 && rc.getTeamBullets() > 20 && rc.getRoundNum() > 50)) {
            int toKeep = turnsUntilVictory < 50 ? 0 : 200;
            int shouldBuy = (int)Math.floor((rc.getTeamBullets() - toKeep)/ cost);
            double donate = shouldBuy * cost + 0.0001;
            if (donate > 0) {
                rc.donate((float)donate);
            }
        }
    }

    void fireAtNearbyTree(TreeInfo[] trees) throws GameActionException {
        Team enemy = rc.getTeam().opponent();
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        for (TreeInfo tree : trees) {
            if (Clock.getBytecodesLeft() < 2500)
                break;
            if (tree.getTeam() == enemy) {
                BodyInfo firstUnitHit = linecast(tree.location);
                if (firstUnitHit != null && firstUnitHit.isTree() && ((TreeInfo)firstUnitHit).getTeam() == enemy) {
                    TreeInfo t = (TreeInfo)firstUnitHit;
                    if (rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME && t.getHealth() > 20 &&
                            (t.getHealth() < 45 || t.location.distanceTo(rc.getLocation()) < 3f)) {
                        rc.fireSingleShot(rc.getLocation().directionTo(tree.location));
                    }
                }
            }
        }
    }
}
