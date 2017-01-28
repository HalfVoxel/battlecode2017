package bot;

import battlecode.common.*;

import java.util.*;

abstract class Robot {
    static RobotController rc = null;

    /**
     * rc.getType, should ideally be called 'type' but that is unfortunately a keyword
     */
    static RobotType type = null;
    static MapLocation spawnPos = null;
    static Random rnd;
    int roundAtStart = 0;

    static Team enemy;
    static Team ally;
    static MapLocation[] initialArchonLocations;
    static MapLocation[] ourInitialArchonLocations;

    static final int MAP_EDGE_BROADCAST_OFFSET = 10;
    static final int HIGH_PRIORITY_TARGET_OFFSET = 20;
    static final int GARDENER_OFFSET = 25;
    static final int GARDENER_CAN_PROBABLY_BUILD = 40;
    static final int PRIMARY_UNIT = 50;
    static final int TARGET_OFFSET = 60;
    static final int NUMBER_OF_TARGETS = 4;
    static final int PATHFINDING = 3000;
    static final int PATHFINDING_RESULT_TO_ENEMY_ARCHON = 4000;
    static final int PATHFINDING_TREE = 5000;
    static final int HIGH_PRIORITY = 6000;
    static final int ARCHON_BUILD_SCORE = 7001;

    static final int[] dx = new int[]{1, 0, -1, 0};
    static final int[] dy = new int[]{0, 1, 0, -1};

    private static final int EXPLORATION_OFFSET = 100;
    static final float PATHFINDING_NODE_SIZE = 2.01f;
    static final int PATHFINDING_CHUNK_SIZE = 4;
    static final int PATHFINDING_WORLD_WIDTH = 100;
    private static final float PATHFINDING_CHUNK_RADIUS = PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE * 0.707106781f + 0.001f;

    static final int EXPLORATION_ORIGIN = EXPLORATION_OFFSET;

    static final int STOP_SPENDING_AT_TIME = 50;

    static int mapEdgesDetermined = 0;
    static float mapEdges0, mapEdges1, mapEdges2, mapEdges3;
    boolean countingAsAlive = true;
    private final Map<Integer, Float> bulletHitDistance = new HashMap<>();
    private final Map<Integer, MapLocation> unitLastLocation = new HashMap<>();

    static MapLocation explorationOrigin;

    public static void init(RobotController rc) throws GameActionException {
        Robot.rc = rc;
        rnd = new Random(System.getProperty("bc.testing.seed", "0").hashCode());
        type = rc.getType();
        spawnPos = rc.getLocation();

        // Conservative map edges
        mapEdges0 = Math.min(spawnPos.x, 500f) + GameConstants.MAP_MAX_WIDTH;
        mapEdges1 = Math.min(spawnPos.y, 500f) + GameConstants.MAP_MAX_WIDTH;
        mapEdges2 = Math.max(spawnPos.x - GameConstants.MAP_MAX_WIDTH, 0f);
        mapEdges3 = Math.max(spawnPos.y - GameConstants.MAP_MAX_WIDTH, 0f);

        ally = rc.getTeam();
        enemy = ally.opponent();
        ourInitialArchonLocations = rc.getInitialArchonLocations(ally);
        initialArchonLocations = rc.getInitialArchonLocations(enemy);

        // Set the exploration origin if it has not been set already
        if (readBroadcastLong(EXPLORATION_ORIGIN) == 0L) {
            onStartOfGame();
        } else {
            explorationOrigin = readBroadcastPosition(EXPLORATION_ORIGIN);
        }
    }

    /**
     * Called once at the start of the game (only for a single unit).
     */
    static void onStartOfGame() throws GameActionException {
        explorationOrigin = rc.getLocation().translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);
        broadcast(EXPLORATION_ORIGIN, explorationOrigin);

        rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (0 + 1), mapEdges0);
        rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (1 + 1), mapEdges1);
        rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (2 + 1), mapEdges2);
        rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (3 + 1), mapEdges3);

        // Reset some broadcast values
        rc.broadcast(GARDENER_OFFSET, -1000);
        rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);

        // Ensure we have count the number of alive archons correctly
        rc.broadcast(RobotType.ARCHON.ordinal(), rc.getInitialArchonLocations(ally).length);
    }

    /**
     * Called once when the unit first starts to run code.
     * Executed just before the first call to onUpdate.
     */
    void onAwake() throws GameActionException {
    }

    /**
     * Called repeatedly until the unit dies or the game ends.
     * A single invocation may take longer than one tick.
     */
    abstract void onUpdate() throws GameActionException;

    /**
     * Returns a random Direction
     *
     * @return a random Direction
     */
    protected static Direction randomDirection() {
        return new Direction(rnd.nextFloat() * 2 * (float)Math.PI);
    }

    protected static int spawnedCount(RobotType tp) throws GameActionException {
        return rc.readBroadcast(tp.ordinal());
    }

    /**
     * Adds the ID to a global shared set of all high priority entities
     *
     * @param id ID of the entity, assumed to be in the range 0...32000.
     */
    void markAsHighPriority(int id) throws GameActionException {
        // Note that the right shift by id will only use the last 5 bits
        // and thus this will pick out the id'th bit in the broadcast array when starting
        // from the offset HIGH_PRIORITY
        // Broadcast that index but with the bit set
        rc.broadcast(HIGH_PRIORITY + (id >> 5), rc.readBroadcast(HIGH_PRIORITY + (id >> 5)) | (1 << id));
    }

    boolean isHighPriority(int id) throws GameActionException {
        return ((rc.readBroadcast(HIGH_PRIORITY + (id >> 5)) >> id) & 1) != 0;
    }

    /**
     * Attempts to move in a given direction, while avoiding small obstacles directly in the path.
     *
     * @param dir The intended direction of movement
     * @return true if a move was performed
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
     */
    boolean tryMove(Direction dir, float degreeOffset, int checksPerSide, float distance) throws GameActionException {
        // First, try intended direction
        if (rc.canMove(dir, distance)) {
            rc.move(dir, distance);
            return true;
        }
        // Now try a bunch of similar angles
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
        if (Clock.getBytecodesLeft() > 1000) broadcastEnemyLocations();

        if (rc.getRoundNum() != roundAtStart) {
            System.out.println("Error! Did not finish within the bytecode limit");
        }

        Clock.yield();
        onStartOfTick();
    }

    MapLocation nextPointOnPathToEnemyArchon(MapLocation loc) throws GameActionException {
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
        return explorationOrigin.translate((tx + 0.5f) * PATHFINDING_NODE_SIZE, (ty + 0.5f) * PATHFINDING_NODE_SIZE);
    }

    static int pathfindingChunkDataForNode(int nodeX, int nodeY) throws GameActionException {
        int cx = nodeX / PATHFINDING_CHUNK_SIZE;
        int cy = nodeY / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        return rc.readBroadcast(PATHFINDING + index);
    }

    boolean isNodeReserved(int x, int y) throws GameActionException {
        int cx = x / PATHFINDING_CHUNK_SIZE;
        int cy = y / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        return ((rc.readBroadcast(PATHFINDING_TREE + index) >> ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE))) & 1) != 0;
    }

    void reserveNode(int x, int y) throws GameActionException {
        int cx = x / PATHFINDING_CHUNK_SIZE;
        int cy = y / PATHFINDING_CHUNK_SIZE;
        int index = cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx;
        int val = rc.readBroadcast(PATHFINDING_TREE + index);
        rc.setIndicatorDot(nodePosition(x, y), 0, 0, 255);
        rc.broadcast(PATHFINDING_TREE + index, val | 1 << ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE)));
    }

    /**
     * Returns node index for the closest node
     */
    static int snapToNode(MapLocation loc) {
        MapLocation relativePos = loc.translate(-explorationOrigin.x, -explorationOrigin.y);
        int x = (int)Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int y = (int)Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        return y * PATHFINDING_WORLD_WIDTH + x;
    }

    /**
     * Returns a bitpacked value where bit 0 indicates if the node is blocked and bit 1 indicates if the node chunk has been fully explored yet
     */
    static int nodeInfo(int x, int y) throws GameActionException {
        int chunk = pathfindingChunkDataForNode(x, y);
        int blocked = (chunk >> ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE))) & 1;
        int fullyExplored = (chunk >>> 30) & 0x2;
        return fullyExplored | blocked;
    }

    static MapLocation nodePosition(int x, int y) {
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

        //noinspection NumericOverflow
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

            if (!traversable) {
                jobChunkInfo |= 1 << jobChunkNodeIndex;
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

    /**
     * True if the location is on the map using the information known so far
     */
    boolean onMap(MapLocation pos) {
        return pos.x <= mapEdges0 && pos.y <= mapEdges1 && pos.x >= mapEdges2 && pos.y >= mapEdges3;
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

    void broadcastEnemyLocations() throws GameActionException {
        int priority = 0;
        float friendlyMilitaryUnits = 0;
        float maxScore = 0;
        MapLocation maxScoreLocation = null;
        RobotInfo[] robots = rc.senseNearbyRobots();
        for (RobotInfo robot : robots) {
            if (robot.getTeam() == enemy) {
                float score = 0;
                switch (robot.type) {
                    case ARCHON:
                        score = 0;
                        break;
                    case GARDENER:
                        score = 150;
                        break;
                    case LUMBERJACK:
                        score = 0;
                        break;
                    case SCOUT:
                        score = 0;
                        break;
                    case SOLDIER:
                        score = 0;
                        break;
                    case TANK:
                        score = 0;
                        break;
                }
                if (score > maxScore) {
                    maxScore = score;
                    maxScoreLocation = robot.location;
                }
                priority += score;
            } else {
                if (robot.type != RobotType.ARCHON && robot.type != RobotType.GARDENER) {
                    friendlyMilitaryUnits += 1;
                }
            }
        }
        priority /= Math.sqrt(friendlyMilitaryUnits + 2);
        if (maxScoreLocation != null) {
            int index = -1;
            boolean isBetter = true;
            for (int i = 0; i < NUMBER_OF_TARGETS; ++i) {
                int offset = TARGET_OFFSET + 10 * i;
                int timeSpotted = rc.readBroadcast(offset);
                int lastEventPriority = rc.readBroadcast(offset + 1);
                MapLocation loc = readBroadcastPosition(offset + 2);
                if (lastEventPriority / (20 + rc.getRoundNum() - timeSpotted) < priority / 20 && loc.distanceTo(maxScoreLocation) < 20f) {
                    index = i;
                    rc.broadcast(offset + 1, 0);
                }
                if (lastEventPriority / (20 + rc.getRoundNum() - timeSpotted) >= priority / 20 && loc.distanceTo(maxScoreLocation) < 20f) {
                    isBetter = false;
                }
                if (lastEventPriority == 0) {
                    index = i;
                }
            }
            if (isBetter && index != -1) {
                int offset = TARGET_OFFSET + 10 * index;
                rc.broadcast(offset, rc.getRoundNum());
                rc.broadcast(offset + 1, priority);
                broadcast(offset + 2, maxScoreLocation);
            }
        }
        for (int i = 0; i < NUMBER_OF_TARGETS; ++i) {
            int offset = TARGET_OFFSET + 10 * i;
            //int timeSpotted = rc.readBroadcast(offset);
            int lastEventPriority = rc.readBroadcast(offset + 1);
            MapLocation loc = readBroadcastPosition(offset + 2);
            if (loc.distanceTo(rc.getLocation()) < 4f && 2 * priority < lastEventPriority) {
                rc.broadcast(offset + 1, 2 * priority);
            }
        }
    }

    static void broadcast(int channel, MapLocation pos) throws GameActionException {
        rc.broadcastFloat(channel, pos.x);
        rc.broadcastFloat(channel + 1, pos.y);
    }

    static void broadcast(int channel, long v) throws GameActionException {
        rc.broadcast(channel, (int)(v >>> 32));
        rc.broadcast(channel + 1, (int)v);
    }

    static long readBroadcastLong(int channel) throws GameActionException {
        return ((long)rc.readBroadcast(channel) << 32) | (long)rc.readBroadcast(channel + 1);
    }

    static MapLocation readBroadcastPosition(int channel) throws GameActionException {
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

    void debug_setIndicatorDot(MapLocation pos, float value) {
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
                if (tree.team == Team.NEUTRAL || tree.team == ally) {
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
     * @return Plan for firing. Call plan.apply to actually fire the bullets.
     */
    FirePlan fireAtNearbyRobot(RobotInfo[] friendlyRobots, RobotInfo[] hostileRobots, boolean targetArchons) throws GameActionException {
        if (hostileRobots.length == 0) return null;

        MapLocation myLocation = rc.getLocation();
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        List<Integer> bestRobotsTried = new ArrayList<>();
        for (int attemptN = 0; attemptN < 1; ++attemptN) {
            RobotInfo bestRobot = null;
            float bestScore2 = 0;

            for (RobotInfo robot : hostileRobots) {
                if (bestRobotsTried.contains(robot.ID))
                    continue;
                MapLocation lastLoc = unitLastLocation.get(robot.getID());
                if (lastLoc == null) {
                    lastLoc = robot.location;
                    unitLastLocation.put(robot.getID(), lastLoc);
                }
                if (!lastLoc.equals(robot.location) && lastLoc.x >= 0) {
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
                        score += 250;
                        break;
                    case TANK:
                        score += 200;
                    default:
                        score += 80;
                        break;
                }
                if (lastLoc.x >= 0) {
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
                if (rc.getType() == RobotType.SCOUT) {
                    if (bestRobot.getType() == RobotType.SCOUT || bestRobot.getType() == RobotType.GARDENER) {
                        if (myLocation.distanceTo(bestRobot.location) > 3f)
                            break;
                    } else {
                        break;
                    }
                }
                bestRobotsTried.add(bestRobot.ID);

                BodyInfo firstUnitHit = linecast(bestRobot.location);
                if (rc.getLocation().distanceTo(bestRobot.location) < 2 * type.sensorRadius && teamOf(firstUnitHit) == enemy && turnsLeft > STOP_SPENDING_AT_TIME) {
                    Direction dir = rc.getLocation().directionTo(bestRobot.location);
                    if (rc.canFirePentadShot() && rc.getTeamBullets() > 300 && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
                        //rc.firePentadShot(dir);
                        return new FirePlan(dir, 5);
                    }

                    if (rc.canFirePentadShot() && rc.getLocation().distanceTo(bestRobot.location) < 4.5f) {
                        //rc.firePentadShot(dir);
                        return new FirePlan(dir, 5);
                    }

                    if (rc.canFireTriadShot() && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
                        //rc.fireTriadShot(dir);
                        return new FirePlan(dir, 3);
                    }

                    if (rc.canFireTriadShot() && rc.getLocation().distanceTo(bestRobot.location) < 5.5f) {
                        //rc.fireTriadShot(dir);
                        return new FirePlan(dir, 3);
                    }

                    // And we have enough bullets, and haven't attacked yet this turn...
                    if (rc.canFireSingleShot()) {
                        // ...Then fire a bullet in the direction of the enemy.
                        //rc.fireSingleShot(dir);
                        return new FirePlan(dir, 1);
                    }

                    break;
                }
            } else {
                break;
            }
            if (Clock.getBytecodesLeft() < 3000)
                break;
        }
        return null;
    }

    /**
     * Return value in [bullets/tick]
     */
    static float treeScore(TreeInfo tree, MapLocation fromPos) {
        float turnsToChopDown = 1f;
        turnsToChopDown += (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE);
        if (fromPos != null) turnsToChopDown += Math.sqrt(fromPos.distanceTo(tree.location) / type.strideRadius);

        return ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + 1) / turnsToChopDown;
    }

    static Team teamOf(BodyInfo b) {
        if (b != null && b.isRobot()) return ((RobotInfo)b).team;
        if (b != null && b.isTree()) return ((TreeInfo)b).team;
        return Team.NEUTRAL;
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
     * Distance to first body on the line segment going from the edge of this robot to the specified location.
     * Uses sampling so it is not perfectly accurate.
     * Also uses the radius of the robot for collision detection.
     */
    float linecastDistance(MapLocation b) throws GameActionException {
        MapLocation a = rc.getLocation();
        Direction dir = a.directionTo(b);
        float dist = a.distanceTo(b);
        dist = Math.min(dist, type.sensorRadius * 0.99f - type.bodyRadius);

        float offset = Math.min(type.bodyRadius + 0.001f, dist);
        a = a.add(dir, offset);
        dist -= offset;

        if (dist <= 0) {
            return 0f;
        }

        final float desiredDx = 0.5f;
        int steps = (int)(dist / desiredDx);
        float dx = dist / (float)steps;

        float radius = type.bodyRadius;
        for (int t = 1; t <= steps; t++) {
            float d = t * dx;
            MapLocation p = a.add(dir, d);
            if (rc.isCircleOccupiedExceptByThisRobot(p, radius)) {
                if (rc.isCircleOccupiedExceptByThisRobot(a.add(dir, d - 0.75f * dx), radius)) return d - 0.75f * dx;
                if (rc.isCircleOccupiedExceptByThisRobot(a.add(dir, d - 0.5f * dx), radius)) return d - 0.5f * dx;
                if (rc.isCircleOccupiedExceptByThisRobot(a.add(dir, d - 0.25f * dx), radius)) return d - 0.25f * dx;
                return d;
            }
        }

        return dist;
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

    Direction lastBugDir;
    final MapLocation[] positionHistory = new MapLocation[40];
    int positionHistoryIndex = 0;
    int bugTiebreaker = 0;

    void distBug(MapLocation target) throws GameActionException {
        final float step = 0.2f;

        float distanceToTarget = rc.getLocation().distanceTo(target);

        // Already at target
        if (distanceToTarget <= 0.00001f) return;

        if (lastBugDir == null) lastBugDir = rc.getLocation().directionTo(target);

        final float dtheta = 6;
        final int steps = (int)(360 / dtheta);

        if (rc.canMove(lastBugDir) && bugTiebreaker != 0) {
            // Turn as far as we can
            boolean any = false;
            for (int i = 0; i < steps; i++) {
                Direction nextDir = lastBugDir.rotateLeftDegrees(bugTiebreaker * dtheta);
                if (rc.canMove(nextDir)) {
                    lastBugDir = nextDir;
                } else {
                    any = true;
                    break;
                }
            }

            // No obstacle nearby, just move to the target
            if (!any) {
                lastBugDir = rc.getLocation().directionTo(target);
            }
        } else {
            if (bugTiebreaker == 0) {
                Direction tieBreakerDirection = rc.getLocation().directionTo(nextPointOnPathToEnemyArchon(rc.getLocation()));
                if (tieBreakerDirection.rotateRightDegrees(90).degreesBetween(lastBugDir) > 90 && rnd.nextFloat() < 0.7f) {
                    // Rotate right
                    bugTiebreaker = 1;
                } else {
                    // Rotate left
                    bugTiebreaker = -1;
                }
            }

            for (int i = 0; i < steps; i++) {
                Direction nextDir = lastBugDir.rotateRightDegrees(bugTiebreaker * dtheta * i);
                if (rc.canMove(nextDir)) {
                    lastBugDir = nextDir;
                    break;
                }
            }
        }

        if (rc.getRoundNum() % 3 == 0) {
            positionHistory[positionHistoryIndex] = rc.getLocation();
            positionHistoryIndex = (positionHistoryIndex + 1) % positionHistory.length;
        }

        float historicalMinDist = distanceToTarget;
        for (MapLocation pos : positionHistory) {
            if (pos != null) {
                //rc.setIndicatorDot(positionHistory[i], 0, 0, 0);
                historicalMinDist = Math.min(historicalMinDist, pos.distanceTo(target));
            }
        }

        if (rc.canMove(rc.getLocation().directionTo(target))) {
            float hitDistance = linecastDistance(target);

            if (distanceToTarget - hitDistance < historicalMinDist - step) {
                // Move straight to the target
                lastBugDir = rc.getLocation().directionTo(target);
                bugTiebreaker = 0;
            }
        }

        rc.setIndicatorLine(rc.getLocation(), target, 0, 0, 0);
        //rc.setIndicatorLine(rc.getLocation(), rc.getLocation().add(lastBugDir, 5), 255, 255, 255);

        if (rc.canMove(lastBugDir)) {
            rc.move(lastBugDir);
        }
    }

    int fallbackMovementDirection = 1;
    float fallbackDirectionLimit = 0.5f;
    int movementBlockedTicks = 0;

    /**
     * Moves toward the target and turns to follow object contours if necessary
     */
    void optimisticBug(MapLocation target, BulletInfo[] bullets, RobotInfo[] units) throws GameActionException {
        Direction desiredDir = rc.getLocation().directionTo(target);
        float desiredStride = Math.min(rc.getLocation().distanceTo(target), type.strideRadius);

        // Already at target
        if (desiredDir == null) return;

        final int steps = 12;
        float radiansPerStep = fallbackMovementDirection * fallbackDirectionLimit * (float)Math.PI / (float)steps;
        for (int i = 0; i < steps; i++) {
            float angle = i * radiansPerStep;
            Direction dir = desiredDir.rotateLeftRads(angle);
            //rc.setIndicatorLine(rc.getLocation(), rc.getLocation().add(dir, type.strideRadius), 200, fallbackMovementDirection > 0 ? 255 : 0, 200);
            if (rc.canMove(dir, desiredStride)) {
                if (angle <= Math.PI * 0.5f + 0.001f) {
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
                optimisticBug(target, bullets, units);
            } else {
                movementBlockedTicks += 1;
                if (movementBlockedTicks > 6) {
                    fallbackMovementDirection *= -1;
                    movementBlockedTicks = 0;
                    optimisticBug(target, bullets, units);
                }
            }
        }
    }

    MapLocation previousBestMove;

    final float[] bulletX = new float[100];
    final float[] bulletY = new float[100];
    final float[] bulletDx = new float[100];
    final float[] bulletDy = new float[100];
    final float[] bulletDamage = new float[100];
    final float[] bulletSpeed = new float[100];
    final MapLocation[] movesToConsider = new MapLocation[100];

    MapLocation moveToAvoidBullets(MapLocation secondaryTarget, BulletInfo[] bullets, RobotInfo[] units) throws GameActionException {
        if (rc.hasMoved()) return null;

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
            distBug(secondaryTarget);
            // optimisticBug(secondaryTarget)
            return null;
        }

        MapLocation myLocation = rc.getLocation();
        int numMovesToConsider = 0;

        if (secondaryTarget != null && myLocation.distanceTo(secondaryTarget) > 0) {
            movesToConsider[numMovesToConsider++] = myLocation.add(myLocation.directionTo(secondaryTarget), Math.min(myLocation.distanceTo(secondaryTarget), type.strideRadius));
            if (rc.getType() == RobotType.LUMBERJACK) {
                movesToConsider[numMovesToConsider++] = myLocation;
            }
        } else {
            movesToConsider[numMovesToConsider++] = myLocation;
        }

        if (units.length > 0) {
            // Move away from unit
            //Direction dir = myLocation.directionTo(units[0].location);
            //movesToConsider[numMovesToConsider++] = myLocation.add(dir.opposite(), type.strideRadius);
            float dist = units[0].location.distanceTo(myLocation);
            Direction dir = units[0].location.directionTo(myLocation).rotateRightRads(type.strideRadius / dist);
            movesToConsider[numMovesToConsider++] = units[0].location.add(dir, dist);
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
            return bestMove;
        }
        return null;
    }

    float getDefensiveBulletAvoidanceScore(MapLocation loc, MapLocation reservedNodeLoc, int numBullets,
                                           float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                           float[] bulletDamage, float[] bulletSpeed,
                                           RobotInfo[] units, MapLocation target) {


        Team myTeam = ally;
        float score = 0f;
        boolean ignoreTarget = false;
        boolean isCoward = rc.getHealth() < type.maxHealth * 0.3f;

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
                } else {
                    score += 4f / (loc.distanceTo(tree.location));
                }
            }
            for (RobotInfo unit : units) {
                float dis = loc.distanceTo(unit.location);
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    score -= 6f / (dis + 1);
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
        } else if (type == RobotType.ARCHON) {
            for (RobotInfo unit : units) {
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    if (unit.type == RobotType.ARCHON)
                        score -= 10 / (unit.location.distanceTo(loc) + 1);
                    else
                        score -= 3 / (unit.location.distanceTo(loc) + 1);
                } else {
                    switch (unit.type) {
                        case ARCHON:
                            // Don't do anything
                            break;
                        case GARDENER:
                            score += 2f / (loc.distanceSquaredTo(unit.location) + 1);
                            break;
                        default: // Scout/Soldier/Tank
                            // Note: Potential should be positive for some point within the sensor radius otherwise we will just flee
                            if (isCoward)
                                score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                            else
                                score += 1f / (loc.distanceSquaredTo(unit.location) + 1);
                            break;
                        case LUMBERJACK:
                            float dis = loc.distanceTo(unit.location);
                            // Note: Potential should be positive for some point within the sensor radius otherwise we will just flee
                            score -= 10f / (dis * dis + 1);
                            if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f) {
                                score -= 1000;
                            }
                            break;
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
                        case ARCHON:
                            // Don't do anything
                            break;
                        case GARDENER:
                            score += 6f / (loc.distanceSquaredTo(unit.location) + 1);
                            break;
                        case SCOUT:
                            float dis = loc.distanceTo(unit.location);
                            if (isCoward)
                                score += 0.5 / (dis + 1) - 2f / (dis * dis + 1);
                            else
                                score += 1 / (dis + 1);
                            break;
                        default: // Soldier/Tank
                            dis = loc.distanceTo(unit.location);
                            if (isCoward)
                                score += 1 / (dis + 1) - 7f / (dis * dis + 1);
                            else
                                score += 1.5 / (dis + 1) - 6f / (dis * dis + 1);
                            break;
                        case LUMBERJACK:
                            dis = loc.distanceTo(unit.location);
                            // Note: Potential should be positive for some point within the sensor radius otherwise we will just flee
                            score += 1.5 / (dis + 1) - 8f / (dis * dis + 1);
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

        // Finally make sure the bullet has not already passed us
        return !(dot < 0 && sqrRadius - sqrDistanceToLineOfTravel < dot * dot);
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

    static <T> T randomChoice(T[] values, T def) {
        return values.length > 0 ? values[(int)(rnd.nextFloat() * values.length)] : def;
    }

    void considerDonating() throws GameActionException {
        double cost = rc.getVictoryPointCost();
        double income = rc.getTreeCount() + 2;
        double victoryPointsLeft = GameConstants.VICTORY_POINTS_TO_WIN - rc.getTeamVictoryPoints();
        double turnsUntilVictory = (victoryPointsLeft * cost) / income;
        int gardenerCount = spawnedCount(RobotType.GARDENER);
        int archonCount = spawnedCount(RobotType.ARCHON);
        if (turnsUntilVictory < 200 || rc.getRoundNum() > rc.getRoundLimit() - 300 || rc.getTeamBullets() > 2000 ||
                (gardenerCount == 0 && archonCount == 0 && rc.getTeamBullets() > 20 && rc.getRoundNum() > 50)) {
            int toKeep = turnsUntilVictory < 50 ? 0 : 20;
            int shouldBuy = (int)Math.floor((rc.getTeamBullets() - toKeep) / cost);
            double donate = shouldBuy * cost + 0.0001;
            if (donate > 0) {
                rc.donate((float)donate);
            }
        }
    }

    void fireAtNearbyTree(TreeInfo[] trees) throws GameActionException {
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        for (TreeInfo tree : trees) {
            if (Clock.getBytecodesLeft() < 2500)
                break;
            if (tree.getTeam() == enemy) {
                BodyInfo firstUnitHit = linecast(tree.location);
                if (firstUnitHit != null && firstUnitHit.isTree() && ((TreeInfo)firstUnitHit).getTeam() == enemy) {
                    TreeInfo t = (TreeInfo)firstUnitHit;
                    if (rc.canFireSingleShot() && turnsLeft > STOP_SPENDING_AT_TIME && t.getHealth() > 10 &&
                            (t.getHealth() < 45 || t.location.distanceTo(rc.getLocation()) < 3f)) {
                        rc.fireSingleShot(rc.getLocation().directionTo(tree.location));
                    }
                }
            }
        }
    }
}
