package bot;

import battlecode.common.*;

import java.util.*;

abstract class Robot {
    RobotController rc = null;
    RobotType info = null;
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
    static final int HAS_SEEN = 1000;

    private static final int EXPLORATION_OFFSET = 100;
    private static final int EXPLORATION_CHUNK_SIZE = 25;

    static final int EXPLORATION_ORIGIN = EXPLORATION_OFFSET + 0;
    private static final int EXPLORATION_EXPLORED = EXPLORATION_OFFSET + 2;
    private static final int EXPLORATION_OUTSIDE_MAP = EXPLORATION_OFFSET + 4;

    static final int STOP_SPENDING_AT_TIME = 50;

    int mapEdgesDetermined = 0;
    float[] mapEdges = new float[4];
    boolean countingAsAlive = true;
    private Map<Integer, Float> bulletHitDistance = new HashMap<>();

    void init() throws GameActionException {
        info = rc.getType();
        spawnPos = rc.getLocation();
        onStartOfTick();
    }

    abstract void run() throws GameActionException;

    /**
     * Returns a random Direction
     *
     * @return a random Direction
     */
    protected Direction randomDirection() {
        return new Direction(rnd.nextFloat() * 2 * (float) Math.PI);
    }

    protected int spawnedCount(RobotType tp) throws GameActionException {
        return rc.readBroadcast(tp.ordinal());
    }

    /**
     * Adds the ID to a global shared set of all detected entities and returns if this was
     * the first time that entity was detected.
     * @param id ID of the entity, assumed to be in the range 0...32000.
     * @return True if this was the first time this particular ID was detected. False otherwise.
     * @throws GameActionException
     */
    boolean detect (int id) throws GameActionException {
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
        if (Clock.getBytecodesLeft() > 1000) broadcastEnemyLocations(null);

        if (rc.getRoundNum() != roundAtStart) {
            System.out.println("Error! Did not finish within the bytecode limit");
        }

        Clock.yield();
        onStartOfTick();
    }

    void broadcastExploration() throws GameActionException {
        // Determine chunk
        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN);
        MapLocation relativePos = rc.getLocation().translate(-origin.x, -origin.y);
        int cx = (int) Math.floor(relativePos.x / EXPLORATION_CHUNK_SIZE + 4);
        int cy = (int) Math.floor(relativePos.y / EXPLORATION_CHUNK_SIZE + 4);
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

    /**
     * True if the location is on the map using the information known so far
     */
    boolean onMap(MapLocation pos) {
        return ((mapEdgesDetermined & 1) == 0 || pos.x <= mapEdges[0]) &&
                ((mapEdgesDetermined & 2) == 0 || pos.y <= mapEdges[1]) &&
                ((mapEdgesDetermined & 4) == 0 || pos.x >= mapEdges[2]) &&
                ((mapEdgesDetermined & 8) == 0 || pos.y >= mapEdges[3]);
    }

    /**
     * True if the location is at least margin units from the edge of the map using the information known so far
     */
    boolean onMap(MapLocation pos, float margin) {
        return ((mapEdgesDetermined & 1) == 0 || pos.x <= mapEdges[0] - margin) &&
                ((mapEdgesDetermined & 2) == 0 || pos.y <= mapEdges[1] - margin) &&
                ((mapEdgesDetermined & 4) == 0 || pos.x >= mapEdges[2] + margin) &&
                ((mapEdgesDetermined & 8) == 0 || pos.y >= mapEdges[3] + margin);
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
        if ((mapEdgesDetermined & 1) != 0) x = Math.min(x, mapEdges[0] - margin);
        if ((mapEdgesDetermined & 2) != 0) y = Math.min(y, mapEdges[1] - margin);
        if ((mapEdgesDetermined & 4) != 0) x = Math.max(x, mapEdges[2] + margin);
        if ((mapEdgesDetermined & 8) != 0) y = Math.max(y, mapEdges[3] + margin);
        return new MapLocation(x, y);
    }

    float getDistanceToMapEdge(MapLocation pos) {
        float ret = 10f;
        if ((mapEdgesDetermined & 1) != 0)
            ret = Math.min(ret, mapEdges[0] - pos.x);
        if ((mapEdgesDetermined & 2) != 0)
            ret = Math.min(ret, mapEdges[1] - pos.y);
        if ((mapEdgesDetermined & 4) != 0)
            ret = Math.min(ret, pos.x - mapEdges[2]);
        if ((mapEdgesDetermined & 8) != 0)
            ret = Math.min(ret, pos.y - mapEdges[3]);
        return ret;
    }

    void broadcastEnemyLocations(RobotInfo[] nearbyEnemies) throws GameActionException {
        Team enemy = rc.getTeam().opponent();

        if (nearbyEnemies == null) {
            nearbyEnemies = rc.senseNearbyRobots(info.sensorRadius, enemy);
        }

        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
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
                            (rc.getRoundNum() > previousTick + 5 && (rc.getType() == RobotType.GARDENER || (rc.getType() == RobotType.ARCHON && rc.getHealth() < rc.getType().maxHealth * 0.5f)) ||
                                    (rc.getRoundNum() > previousTick + 8))) {
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
        if (!anyHostiles && lastAttackingEnemySpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1), info.sensorRadius * 0.7f)) {
            rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        }

        if (!anyGardeners && lastGardenerSpotted != -1000 && rc.getLocation().isWithinDistance(readBroadcastPosition(GARDENER_OFFSET + 1), info.sensorRadius * 0.7f)) {
            rc.broadcast(GARDENER_OFFSET, -1000);
        }
    }

    void broadcast(int channel, MapLocation pos) throws GameActionException {
        rc.broadcast(channel, (int) (pos.x * 1000));
        rc.broadcast(channel + 1, (int) (pos.y * 1000));
    }

    void broadcast(int channel, long v) throws GameActionException {
        rc.broadcast(channel, (int) (v >>> 32));
        rc.broadcast(channel + 1, (int) v);
    }

    long readBroadcastLong(int channel) throws GameActionException {
        return ((long) rc.readBroadcast(channel) << 32) | (long) rc.readBroadcast(channel + 1);
    }

    MapLocation readBroadcastPosition(int channel) throws GameActionException {
        return new MapLocation(rc.readBroadcast(channel) / 1000f, rc.readBroadcast(channel + 1) / 1000f);
    }

    void updateLiveness() throws GameActionException {
        float countAsDeadLimit = rc.getType() == RobotType.SCOUT ? 4 : 10;
        if (countingAsAlive && rc.getHealth() <= countAsDeadLimit) {
            rc.broadcast(info.ordinal(), spawnedCount(info) - 1);
            countingAsAlive = false;
        } else if (!countingAsAlive && rc.getHealth() > countAsDeadLimit) {
            rc.broadcast(info.ordinal(), spawnedCount(info) + 1);
            countingAsAlive = true;
        }
    }

    void setIndicatorDot(MapLocation pos, float value) throws GameActionException {
        float r = Math.max(Math.min(value * 3f, 1f), 0f);
        float g = Math.max(Math.min((value - 1 / 3f) * 3f, 1f), 0f);
        float b = Math.max(Math.min((value - 2 / 3f) * 3f, 1f), 0f);

        rc.setIndicatorDot(pos, (int) (r * 255f), (int) (g * 255f), (int) (b * 255f));
    }

    void shakeNearbyTrees() throws GameActionException {
        if (rc.canShake()) {
            TreeInfo[] trees = rc.senseNearbyTrees(info.bodyRadius + GameConstants.INTERACTION_DIST_FROM_EDGE - 0.001f);
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
            for (int i = 0; i < 4; i++) {
                mapEdges[i] = rc.readBroadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1) / 1000f;
            }
        }

        int tmpDetermined = mapEdgesDetermined;
        for (int i = 0; i < 4; i++) {
            if ((mapEdgesDetermined & (1 << i)) == 0) {
                float angle = i * (float) Math.PI / 2f;
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
                    rc.broadcast(MAP_EDGE_BROADCAST_OFFSET + i + 1, (int) (result * 1000));
                    // This robot will pick up the change for real the next time determineMapSize is called
                    tmpDetermined |= (1 << i);
                    rc.broadcast(MAP_EDGE_BROADCAST_OFFSET, tmpDetermined);
                    System.out.println("Found map edge " + i + " at " + result);

                    rc.setIndicatorLine(mapEdge.add(angle + (float) Math.PI * 0.5f, 50), mapEdge.add(angle - (float) Math.PI * 0.5f, 50), 255, 255, 255);
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
                float score = 0;
                switch(robot.type) {
                    case GARDENER:
                        score += 100;
                        break;
                    case ARCHON:
                        score += targetArchons ? 1f : 0f;
                        break;
                    case SCOUT:
                        score += 150;
                        break;
                    default:
                        if (closestThreat == null || robot.location.distanceTo(myLocation) < closestThreat.distanceTo(myLocation)) {
                            closestThreat = robot.location;
                        }
                        score += 50;
                        break;
                }
                score /= 4 + robot.health / robot.type.maxHealth;
                score /= myLocation.distanceTo(robot.location) + 1;
                if (score > bestScore2) {
                    bestScore2 = score;
                    bestRobot = robot;
                }
            }

            if (bestRobot != null) {
                if(bestRobot.getType() != RobotType.SCOUT && rc.getType() == RobotType.SCOUT){
                    continue;
                }
                lastAttackedEnemyID = bestRobot.getID();
                bestRobotsTried.add(bestRobot.ID);

                BodyInfo firstUnitHit = linecast(bestRobot.location);
                if (rc.getLocation().distanceTo(bestRobot.location) < 2 * info.sensorRadius && teamOf(firstUnitHit) == rc.getTeam().opponent() && turnsLeft > STOP_SPENDING_AT_TIME) {
                    Direction dir = rc.getLocation().directionTo(bestRobot.location);
                    if (rc.canFirePentadShot() && rc.getTeamBullets() > 300 && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
                        rc.firePentadShot(dir);
                    }

                    if (rc.canFireTriadShot() && friendlyRobots.length < hostileRobots.length && (friendlyRobots.length == 0 || hostileRobots.length >= 2)) {
                        // ...Then fire a bullet in the direction of the enemy.
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
            Direction d1 = new Direction(2*(float)Math.PI * i / (float)SWEEP_DIRECTIONS - (float)Math.PI);
            Direction d2 = new Direction(2*(float)Math.PI * (i+1) / (float)SWEEP_DIRECTIONS - (float)Math.PI);
            float radius = rc.getType().bodyRadius*1.5f;
            MapLocation p1 = rc.getLocation().add(d1, radius);
            MapLocation p2 = rc.getLocation().add(d2, radius);

            float value = directionScores[i];
            float r = Math.max(Math.min(value * 3f, 1f), 0f);
            float g = Math.max(Math.min((value - 1 / 3f) * 3f, 1f), 0f);
            float b = Math.max(Math.min((value - 2 / 3f) * 3f, 1f), 0f);

            rc.setIndicatorLine(p1, p2, (int) (r * 255f), (int) (g * 255f), (int) (b * 255f));
        }
    }

    void fireAtNearbyRobotSweep2 (RobotInfo[] robots, TreeInfo[] trees) throws GameActionException {
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
        float distanceToNextTree = numTrees > 0 ? myLocation.distanceTo(trees[0].location) - trees[0].radius*0.2f: 10000;
        float distanceToNextRobot = numRobots > 0 ? myLocation.distanceTo(robots[0].location) : 10000;
        int ri = 0;
        int ti = 0;
        while(ri < numRobots || ti < numTrees) {
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
                start = dir.radians - radiansDelta + (float) Math.PI;
                end = dir.radians + radiansDelta + (float) Math.PI;
                probabilityToHit = 1f;
                pointsForHitting = -0.001f;

                ti++;
                distanceToNextTree = ti < numTrees ? myLocation.distanceTo(trees[ti].location) - trees[0].radius*0.2f : 10000;
            } else {
                // Closest thing is a robot
                RobotInfo robot = robots[ri];

                Direction dir = myLocation.directionTo(robot.location);
                float stride = 0.5f * robot.type.strideRadius;
                float dist = distanceToNextRobot;
                float halfwidth = robot.type.bodyRadius + stride * (dist / bulletSpeed);
                float radiansDelta = halfwidth / dist;

                start = dir.radians - radiansDelta + (float) Math.PI;
                end = dir.radians + radiansDelta + (float) Math.PI;

                probabilityToHit = robot.type.bodyRadius / halfwidth;
                if (robot.team == rc.getTeam()) {
                    pointsForHitting = -0.5f;
                } else {
                    switch(robot.type) {
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

            int startIndex = (int) Math.floor(start * (SWEEP_DIRECTIONS / (2*Math.PI)));
            int endIndex = (int) Math.floor(end * (SWEEP_DIRECTIONS / (2*Math.PI)));

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
                int index = (int)Math.floor((dir.radians + Math.PI) * (SWEEP_DIRECTIONS /(2*Math.PI)));
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

    void fireAtNearbyRobotSweep (RobotInfo[] friendlyRobots, RobotInfo[] hostileRobots, TreeInfo[] trees) throws GameActionException {
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
                start += Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI*2) {
                end -= Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long) (1000 * start) << 32 | eventCount;
            events[eventCount+1] = (long) (1000 * end) << 32 | eventCount + 1;
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
                start += Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI*2) {
                end -= Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long) (1000 * start) << 32 | eventCount;
            events[eventCount+1] = (long) (1000 * end) << 32 | eventCount + 1;
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
                start += Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            if (end > Math.PI*2) {
                end -= Math.PI*2;
                initialEvents[initialEventCount] = eventCount;
                initialEventCount++;
            }

            events[eventCount] = (long) (1000 * start) << 32 | eventCount;
            events[eventCount+1] = (long) (1000 * end) << 32 | eventCount + 1;
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
                while(c != -1) {
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
                while(true) {
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
                while(next[c] != id) {
                    c = next[c];
                }
                next[c] = next[next[c]];
            }
        }


        int w5 = Clock.getBytecodeNum();

        if (hostileRobots.length > 1) {
            System.out.println("Sweep: " + (w2-w1) + " " + (w3-w2) + " " + (w4 - w3) + " " + (w5 - w4) + ": " + hostileRobots.length + " " + friendlyRobots.length + " " + numTrees);
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
        float perpendicularDist = (float) Math.abs(distToRobot * Math.sin(theta)); // soh cah toa :)
        return (perpendicularDist <= rc.getType().bodyRadius);
    }

    /**
     * Return value in [bullets/tick]
     */
    float treeScore(TreeInfo tree, MapLocation fromPos) {
        float turnsToChopDown = 1f;
        turnsToChopDown += (tree.health / GameConstants.LUMBERJACK_CHOP_DAMAGE);
        if (fromPos != null) turnsToChopDown += Math.sqrt(fromPos.distanceTo(tree.location) / info.strideRadius);

        float score = ((tree.containedRobot != null ? tree.containedRobot.bulletCost * 1.5f : 0) + 1) / turnsToChopDown;
        return score;
    }

    Team teamOf(BodyInfo b) {
        if (b != null && b.isRobot()) return ((RobotInfo) b).team;
        if (b != null && b.isTree()) return ((TreeInfo) b).team;
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

        int steps = (int) (dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float) steps);
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
        dist = Math.min(dist, info.sensorRadius * 0.99f);

        float offset = Math.min(info.bodyRadius + 0.001f, dist);
        a = a.add(dir, offset);
        dist -= offset;

        if (dist <= 0) {
            return null;
        }

        int steps = (int) (dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float) steps);
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
        dist = Math.min(dist, info.sensorRadius * 0.99f);

        float offset = Math.min(info.bodyRadius + 0.001f, dist);
        a = a.add(dir, offset);
        dist -= offset;

        if (dist <= 0) {
            return null;
        }

        int steps = (int) (dist / 0.5f);
        for (int t = 1; t <= steps; t++) {
            MapLocation p = a.add(dir, dist * t / (float) steps);
            if (rc.isLocationOccupiedByRobot(p)) {
                RobotInfo robot = rc.senseRobotAtLocation(p);
                if (robot != null && robot.ID != rc.getID()) return robot;
            }
        }

        return null;
    }

    void moveToAvoidBullets(MapLocation secondaryTarget, BulletInfo[] bullets, RobotInfo[] units) throws GameActionException {
        MapLocation myLocation = rc.getLocation();

        List<MapLocation> movesToConsider = new ArrayList<>();
        RobotInfo closestEnemy = null;
        float disToClosestEnemy = 1000000f;

        if (myLocation.distanceTo(secondaryTarget) > 0) {
            movesToConsider.add(myLocation.add(myLocation.directionTo(secondaryTarget), Math.min(myLocation.distanceTo(secondaryTarget), info.strideRadius)));
        } else {
            movesToConsider.add(myLocation);
        }

        for (RobotInfo robot : units) {
            float dist = myLocation.distanceTo(robot.location);
            if (dist < disToClosestEnemy) {
                disToClosestEnemy = dist;
                closestEnemy = robot;
            }
        }

        if (closestEnemy != null) {
            Direction dir = myLocation.directionTo(closestEnemy.location);
            movesToConsider.add(myLocation.add(dir.opposite(), info.strideRadius));
        }

        boolean[] isDangerous = new boolean[bullets.length];
        int numDangerous = 0;
        for (int i = 0; i < bullets.length; i += 1) {
            if (bulletCanHitUs(rc.getLocation(), bullets[i])) {
                isDangerous[i] = true;
                numDangerous += 1;
            } else
                isDangerous[i] = false;
        }
        float[] bulletX = new float[numDangerous];
        float[] bulletY = new float[numDangerous];
        float[] bulletDx = new float[numDangerous];
        float[] bulletDy = new float[numDangerous];
        float[] bulletDamage = new float[numDangerous];
        float[] bulletSpeed = new float[numDangerous];
        int j = 0;
        for (int i = 0; i < bullets.length; i += 1) {
            if (!isDangerous[i])
                continue;
            BulletInfo bullet = bullets[i];
            bulletX[j] = bullet.location.x;
            bulletY[j] = bullet.location.y;
            bulletDx[j] = bullet.getDir().getDeltaX(1);
            bulletDy[j] = bullet.getDir().getDeltaY(1);
            bulletDamage[j] = bullet.getDamage();
            bulletSpeed[j] = bullet.getSpeed();
            j += 1;
        }

        float bestScore = -1000000f;
        MapLocation bestMove = null;
        int iterationsDone = 0;
        while (Clock.getBytecodesLeft() > 3000 || iterationsDone < 2) {
            iterationsDone += 1;
            MapLocation loc;
            if (movesToConsider.isEmpty()) {
                Direction dir = randomDirection();
                float r = rnd.nextFloat();
                if (r < 0.5f)
                    loc = myLocation.add(dir, info.strideRadius);
                else if (r < 0.7f)
                    loc = myLocation.add(dir, info.strideRadius * 0.5f);
                else
                    loc = myLocation.add(dir, 0.2f);
            } else {
                loc = movesToConsider.get(0);
                movesToConsider.remove(0);
            }

            if (rc.canMove(loc)) {
                float score = getDefensiveBulletAvoidanceScore(loc, bulletX, bulletY, bulletDx, bulletDy,
                        bulletDamage, bulletSpeed, units, secondaryTarget);
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = loc;
                }
            }
        }

        // We need to check again that the move is legal, in case we exceeded the byte code limit
        if (bestMove != null && rc.canMove(bestMove)) {
            rc.move(bestMove);
        }
    }

    float getDefensiveBulletAvoidanceScore(MapLocation loc,
                                           float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                           float[] bulletDamage, float[] bulletSpeed,
                                           RobotInfo[] units, MapLocation target) {
        Team myTeam = rc.getTeam();

        float score = 0f;
        boolean ignoreTarget = false;
        if (rc.getType() == RobotType.ARCHON) {
            TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius);
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
        }

        if (!ignoreTarget) {
            score -= 1.15f * loc.distanceTo(target);
        }

        if (rc.getType() == RobotType.LUMBERJACK) {
            for (RobotInfo unit : units) {
                float dis = loc.distanceTo(unit.location);
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    score -= 2f / (dis + 1);
                    if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 1f + unit.getType().bodyRadius) {
                        score -= 100;
                    }
                } else {
                    if (unit.getType() != RobotType.LUMBERJACK) {
                        score += 6f / (dis + 1);
                        if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 1f + unit.getType().bodyRadius) {
                            score += 100;
                        }
                    }
                }
            }
        } else {
            for (RobotInfo unit : units) {
                if (unit.team == myTeam) {
                    if (unit.ID == rc.getID())
                        continue;
                    if (unit.getType() == RobotType.ARCHON || unit.getType() == RobotType.TANK)
                        score -= 1 / (unit.location.distanceTo(loc) + 1);
                    else
                        score -= 0.5 / (unit.location.distanceTo(loc) + 1);
                } else {
                    switch(unit.type) {
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

        score -= 1000f * getEstimatedDamageAtPosition(loc.x, loc.y, bulletX.length, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, null);

        return score;
    }

    /**
     * Determine whether a bullet can possibly hit us
     */
    boolean bulletCanHitUs(MapLocation loc, BulletInfo bullet) {
        float dmg = 0f;
        float radius = info.bodyRadius + info.strideRadius;
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
        float radius = info.bodyRadius;
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
            float intersectionDistDelta = (float) Math.sqrt(sqrRadius - sqrDistanceToLineOfTravel);

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
        return values.length > 0 ? values[(int) (rnd.nextFloat() * values.length)] : null;
    }

    void considerDonating() throws GameActionException{
        double cost = rc.getVictoryPointCost();
        double maxPoints = rc.getTeamVictoryPoints() + Math.floor(rc.getTeamBullets() / cost);
        if (maxPoints >= GameConstants.VICTORY_POINTS_TO_WIN) {
            double donate = Math.floor(rc.getTeamBullets() / cost) * cost;
            rc.donate((float) donate);
        } else if (rc.getRoundNum() > rc.getRoundLimit()-300) {
            double donate = Math.floor((rc.getTeamBullets() - 25) / cost) * cost;
            rc.donate((float) donate);
        } else if (rc.getTeamBullets() > 2000) {
            // Victory points get more expensive over time, so donate them early on
            final int bulletsToKeep = 2000;
            rc.donate(rc.getTeamBullets() - bulletsToKeep);
        }
    }
}
