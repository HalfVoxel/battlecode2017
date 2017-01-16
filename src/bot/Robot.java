package bot;

import battlecode.common.*;

import java.util.*;

abstract class Robot {
    RobotController rc = null;
    RobotType info = null;
    MapLocation spawnPos = null;
    Random rnd = new Random(SEED);

    static final int SEED = 0;
    static final int MAP_EDGE_BROADCAST_OFFSET = 10;
    static final int HIGH_PRIORITY_TARGET_OFFSET = 20;
    static final int GARDENER_OFFSET = 25;
    static final int TREE_OFFSET = 30;

    private static final int EXPLORATION_OFFSET = 100;
    private static final int EXPLORATION_CHUNK_SIZE = 25;

    static final int EXPLORATION_ORIGIN = EXPLORATION_OFFSET + 0;
    private static final int EXPLORATION_EXPLORED = EXPLORATION_OFFSET + 2;
    private static final int EXPLORATION_OUTSIDE_MAP = EXPLORATION_OFFSET + 4;

    int mapEdgesDetermined = 0;
    float[] mapEdges = new float[4];
    boolean countingAsAlive = true;
    private Map<Integer, Float> bulletHitDistance = new HashMap<>();

    void init() {
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
        return new Direction(rnd.nextFloat() * 2 * (float)Math.PI);
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
        updateLiveness();
        if (Clock.getBytecodesLeft() > 1000) determineMapSize();
        if (Clock.getBytecodesLeft() > 1000 || rc.getType() == RobotType.GARDENER) shakeNearbyTrees();
        if (Clock.getBytecodesLeft() > 1000) broadcastEnemyLocations(null);
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
        return ((mapEdgesDetermined & 1) == 0 || pos.x <= mapEdges[0]) &&
                ((mapEdgesDetermined & 2) == 0 || pos.y <= mapEdges[1]) &&
                ((mapEdgesDetermined & 4) == 0 || pos.x >= mapEdges[2]) &&
                ((mapEdgesDetermined & 8) == 0 || pos.y >= mapEdges[3]);
    }

    /** True if the location is at least margin units from the edge of the map using the information known so far */
    boolean onMap (MapLocation pos, float margin) {
        return ((mapEdgesDetermined & 1) == 0 || pos.x <= mapEdges[0] - margin) &&
                ((mapEdgesDetermined & 2) == 0 || pos.y <= mapEdges[1] - margin) &&
                ((mapEdgesDetermined & 4) == 0 || pos.x >= mapEdges[2] + margin) &&
                ((mapEdgesDetermined & 8) == 0 || pos.y >= mapEdges[3] + margin);
    }

    float getDistanceToMapEdge(MapLocation pos){
        float ret = 10f;
        if((mapEdgesDetermined & 1) != 0)
            ret = Math.min(ret, mapEdges[0]-pos.x);
        if((mapEdgesDetermined & 2) != 0)
            ret = Math.min(ret, mapEdges[1]-pos.y);
        if((mapEdgesDetermined & 4) != 0)
            ret = Math.min(ret, pos.x-mapEdges[2]);
        if((mapEdgesDetermined & 8) != 0)
            ret = Math.min(ret, pos.y-mapEdges[3]);
        return ret;
    }

    /** Clamp the location so that it lies on the map using the information known so far */
    MapLocation clampToMap (MapLocation pos) {
        return clampToMap(pos, 0);
    }

    /** Clamp the location so that it lies on the map using the information known so far */
    MapLocation clampToMap (MapLocation pos, float margin) {
        float x = pos.x;
        float y = pos.y;
        if ((mapEdgesDetermined & 1) != 0) x = Math.min(x, mapEdges[0] - margin);
        if ((mapEdgesDetermined & 2) != 0) y = Math.min(y, mapEdges[1] - margin);
        if ((mapEdgesDetermined & 4) != 0) x = Math.max(x, mapEdges[2] + margin);
        if ((mapEdgesDetermined & 8) != 0) y = Math.max(y, mapEdges[3] + margin);
        return new MapLocation(x, y);
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
        rc.broadcast(channel, (int)(v >>> 32));
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
                float angle = i * (float)Math.PI / 2f;
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

                    rc.setIndicatorLine(mapEdge.add(angle + (float)Math.PI*0.5f, 50), mapEdge.add(angle - (float)Math.PI*0.5f, 50), 255, 255, 255);
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

    /** First body on the line segment.
     * Uses sampling so it is not perfectly accurate.
     * \warning Assumes both the start point and the end point are within the sensor radius
     */
    BodyInfo linecastUnsafe (MapLocation a, MapLocation b) throws GameActionException {
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

    /** Distance to first tree.
     * Uses sampling so it is not perfectly accurate.
     *
     * Returns a very large number if no tree was hit.
     */
    float raycastForTree (MapLocation a, Direction dir) throws GameActionException {
        int t = 1;
        boolean hasBeenInside = false;
        while(true) {
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

    void moveToAvoidBullets (MapLocation secondaryTarget, BulletInfo[] bullets, RobotInfo[] units) throws GameActionException {
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
        for(int i=0; i < bullets.length; i += 1){
            if(bulletCanHitUs(rc.getLocation(), bullets[i])) {
                isDangerous[i] = true;
                numDangerous += 1;
            }
            else
                isDangerous[i] = false;
        }
        float[] bulletX = new float[numDangerous];
        float[] bulletY = new float[numDangerous];
        float[] bulletDx = new float[numDangerous];
        float[] bulletDy = new float[numDangerous];
        float[] bulletDamage = new float[numDangerous];
        float[] bulletSpeed = new float[numDangerous];
        int j = 0;
        for(int i=0; i < bullets.length; i += 1){
            if(!isDangerous[i])
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
                if(rc.getType() == RobotType.ARCHON){
                    System.out.println("score = " + score);
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = loc;
                }
            }
        }

        if (bestMove != null) {
            rc.move(bestMove);
        }
    }

    float getDefensiveBulletAvoidanceScore (MapLocation loc,
                                            float[] bulletX, float[] bulletY, float[] bulletDx, float[] bulletDy,
                                            float[] bulletDamage, float[] bulletSpeed,
                                            RobotInfo[] units, MapLocation target) {
        Team myTeam = rc.getTeam();

        float score = 0f;
        float disToEdge = getDistanceToMapEdge(loc);
        if(disToEdge < 10)
            score -= 0.05*(disToEdge-10)*(disToEdge-10);

        if(rc.getType() == RobotType.GARDENER) {
            TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius, rc.getTeam());
            for (TreeInfo tree : trees) {
                if (tree.team == rc.getTeam()) {
                    if (tree.getHealth() < 35) {
                        double add = (50.0 - tree.getHealth() / GameConstants.BULLET_TREE_MAX_HEALTH);
                        add /= loc.distanceTo(tree.location) + 1;
                        score += add;
                    } else {
                        if(rc.getTeamBullets() > 40) {
                            score -= 2 / (loc.distanceTo(tree.location) + 1);
                        }
                    }
                }
                else{
                    score -= 1 / (loc.distanceTo(tree.location) + 1);
                }
            }
        }
        else if(rc.getType() == RobotType.ARCHON){
            TreeInfo[] trees = rc.senseNearbyTrees(info.sensorRadius);
            for (TreeInfo tree : trees) {
                score -= 10*Math.exp(-loc.distanceSquaredTo(tree.location)*0.5);
            }
        }
        else{
            score -= 0.1f/(loc.distanceTo(target)+1);
        }

        for (RobotInfo unit : units) {
            if (unit.team == myTeam) {
                if(unit.ID == rc.getID())
                    continue;
                if(unit.getType() == RobotType.ARCHON || unit.getType() == RobotType.TANK)
                    score -= 1/(unit.location.distanceTo(loc) + 1);
                else
                    score -= 0.5/(unit.location.distanceTo(loc) + 1);
            }
            else{
                if (unit.getType() == RobotType.SCOUT || unit.getType() == RobotType.SOLDIER || unit.getType() == RobotType.TANK) {
                    score -= 2f / (loc.distanceSquaredTo(unit.location) + 1);
                } else if (unit.getType() == RobotType.LUMBERJACK) {
                    float dis = loc.distanceTo(unit.location);
                    score -= 10f / (dis * dis + 1);
                    score += 0.8f / (dis + 1);
                    if (dis < GameConstants.LUMBERJACK_STRIKE_RADIUS + 3f) {
                        score -= 1000;
                    }
                }
            }
        }

        score -= 1000f * getEstimatedDamageAtPosition(loc.x, loc.y, bulletX, bulletY, bulletDx, bulletDy, bulletDamage, bulletSpeed, null);

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
        if(dot < 0 && sqrRadius - sqrDistanceToLineOfTravel < dot*dot){
            // The bullet has already passed us. Everything is ok!
            return false;
        }

        return true;
    }

    /**
     * Estimated damage from bullets when moving to the specified position
     */
    float getEstimatedDamageAtPosition(float locx, float locy, float[] bulletX, float[] bulletY
            , float[] bulletDx, float[] bulletDy, float[] bulletDamage, float[] bulletSpeed, float[] bulletImpactDistances) {
        float dmg = 0f;
        float radius = info.bodyRadius;
        float sqrRadius = radius * radius;

        for (int i = 0; i < bulletX.length; i += 1) {

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

    float[] updateBulletHitDistances (BulletInfo[] nearbyBullets) throws GameActionException {
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

    float bulletImpactDistance (BulletInfo bullet) throws GameActionException {
        if (!bulletHitDistance.containsKey(bullet.getID())) {
            float v = raycastForTree(bullet.location, bullet.dir);
            bulletHitDistance.put(bullet.getID(), v);
            return v;
        } else {
            return bulletHitDistance.get(bullet.getID());
        }
    }

    <T> T randomChoice(T[] values) {
        return values.length > 0 ? values[(int)(rnd.nextFloat()*values.length)] : null;
    }
}
