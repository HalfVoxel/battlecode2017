package bot;

import battlecode.common.*;

class Archon extends Robot {

    static final int STOP_SPENDING_AT_TIME = 100;

    int archonIndex;

    boolean everBuiltAGardener = false;

    @Override
    public void onAwake() throws GameActionException {
        System.out.println("I'm an archon! ");

        for (int i = 0; i < ourInitialArchonLocations.length; i++) {
            if (ourInitialArchonLocations[i].distanceTo(rc.getLocation()) < 2f) {
                archonIndex = i;
            }
        }
        System.out.println("Archon index: " + archonIndex);
    }

    @Override
    public void onUpdate() throws GameActionException {
        int gardenerCount = spawnedCount(RobotType.GARDENER);
        boolean saveForTank = false;
        int tankCount = spawnedCount(RobotType.TANK);
        int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

        if (rc.getTreeCount() > tankCount * 4 + 400 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1) {
            saveForTank = true;
        }

        float buildScore = 0f;
        RobotInfo[] robots = rc.senseNearbyRobots();
        for (RobotInfo robot : robots) {
            if (robot.getTeam() == ally) {
                if (robot.getType() == RobotType.GARDENER) {
                    buildScore -= 1f / (rc.getLocation().distanceTo(robot.location));
                } else {
                    buildScore -= 0.5f / (rc.getLocation().distanceTo(robot.location));
                }
            } else {
                if (robot.getType() != RobotType.GARDENER && robot.getType() != RobotType.ARCHON)
                    buildScore -= 2f / (rc.getLocation().distanceTo(robot.location));
            }
        }
        TreeInfo[] trees = rc.senseNearbyTrees(7f);
        for (TreeInfo tree : trees) {
            if (tree.getTeam() == ally) {
                buildScore -= 0.3f / (rc.getLocation().distanceTo(tree.location));
            } else {
                buildScore -= Math.pow(tree.getRadius(), 1.5) * 0.1f / (rc.getLocation().distanceTo(tree.location));
            }
        }
        float bestBuildScore = -1000000f;
        for (int i = 0; i < initialArchonLocations.length; i += 1) {
            int roundNum = rc.readBroadcast(ARCHON_BUILD_SCORE + 2 * i + 1);
            if (roundNum < rc.getRoundNum() - 2)
                continue;
            if (i == archonIndex)
                continue;
            bestBuildScore = Math.max(bestBuildScore, rc.readBroadcastFloat(ARCHON_BUILD_SCORE + 2 * i));
        }
        boolean isGoodArchon = (buildScore >= bestBuildScore - 0.5);

        boolean gardenersSeemToBeBlocked = rc.readBroadcast(GARDENER_CAN_PROBABLY_BUILD) > gardenerCount * 20 + 10;
        if (rc.hasRobotBuildRequirements(RobotType.GARDENER) && isGoodArchon && (gardenersSeemToBeBlocked || gardenerCount < 1 || rc.getTreeCount() > 4 * gardenerCount || rc.getTeamBullets() > RobotType.TANK.bulletCost + 100) && !saveForTank) {
            // Spend more time trying to find a position to build a gardener on if we haven't built any gardeners yet
            int iterations = gardenerCount == 0 ? 100 : 20;
            boolean couldBuild = tryHireGardener(iterations);

            if (couldBuild) {
                everBuiltAGardener = true;
                if (gardenersSeemToBeBlocked) {
                    System.out.println("Hired gardener because all the existing ones seem to be blocked");
                }

                rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
            } else {
                if (trySacrifice()) {
                    everBuiltAGardener = true;
                    rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                }
                buildScore -= 10000;
            }
        }
        rc.broadcastFloat(ARCHON_BUILD_SCORE + 2 * archonIndex, buildScore);
        rc.broadcast(ARCHON_BUILD_SCORE + 2 * archonIndex + 1, rc.getRoundNum());

        BulletInfo[] bullets = rc.senseNearbyBullets(type.strideRadius + type.bodyRadius + 3f);
        RobotInfo[] units = rc.senseNearbyRobots();
        MapLocation target = everBuiltAGardener ? null : rc.getLocation().add(randomDirection());
        MapLocation moveTo = moveToAvoidBullets(null, bullets, units);
        if (moveTo != null)
            rc.move(moveTo);
        //pathfinding();

        yieldAndDoBackgroundTasks();

        debug_resign();
    }

    public boolean trySacrifice() throws GameActionException {
        // If the game has been running for a while and no archon seem to have been able to build a gardener
        // then try to build a gardener in any direction and destroy this archon
        if (rc.getRoundNum() > 50 && !everBuiltAGardener && spawnedCount(RobotType.GARDENER) == 0 && rc.getRobotCount() <= ourInitialArchonLocations.length) {
            for (int attempts = 0; attempts < 40; attempts++) {
                if (Clock.getBytecodesLeft() < 2000) break;

                Direction dir = randomDirection();
                if (rc.canHireGardener(dir)) {
                    System.out.println("ARCHON SACRIFICE!");
                    rc.hireGardener(dir);
                    int gardenerCount = spawnedCount(RobotType.GARDENER);
                    rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1);

                    rc.disintegrate();
                    return true;
                }
            }

            if (rc.getRoundNum() > 70) {
                RobotInfo[] robots = rc.senseNearbyRobots(rc.getLocation(), type.bodyRadius*2f, ally);
                int nearbyArchons = 0;
                for (RobotInfo robot : robots) {
                    if (robot.type == RobotType.ARCHON) nearbyArchons++;
                }

                if (nearbyArchons > 0 && rc.getRoundNum() > 70 + ourInitialArchonLocations.length - nearbyArchons) {
                    // Still haven't been able to build a gardener
                    // but there are ally archons nearby.
                    // Sacrifice this archon and hope the others will be able to build something.
                    // Start with the archon that has the most ally archons close to it

                    System.out.println("ARCHON SACRIFICE!");
                    rc.disintegrate();
                    return true;
                }
            }
        }

        return false;
    }

    public boolean tryHireGardener(int iterations) throws GameActionException {
        int bestScore = 0;
        Direction bestDir = null;
        for (int attempts = 0; attempts < iterations; attempts++) {
            if (Clock.getBytecodesLeft() < 5000) break;

            // Generate a random direction
            Direction dir = randomDirection();
            MapLocation spawnPos = rc.getLocation().add(dir, type.bodyRadius + RobotType.GARDENER.bodyRadius + GameConstants.GENERAL_SPAWN_OFFSET);
            if (rc.canHireGardener(dir)) {
                rc.setIndicatorDot(spawnPos, 255, 255, 0);
                int score = canGardenerBuildAnythingAtLocation(spawnPos);
                if (score > bestScore) {
                    bestScore = score;
                    bestDir = dir;
                }
            } else {
                rc.setIndicatorDot(spawnPos, 255, 0, 0);
            }
        }

        if (bestDir != null && rc.canHireGardener(bestDir)) {
            // Looks good
            rc.hireGardener(bestDir);
            int gardenerCount = spawnedCount(RobotType.GARDENER);
            rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1);
            return true;
        }

        return false;
    }

    int canGardenerBuildAnythingAtLocation (MapLocation p) throws GameActionException {
        // Try with a lower dtheta and stride if we couldn't find any direction to move in the first time
        float dtheta = 6;

        final int steps = (int)(360 / dtheta);

        int cnt = 0;
        for (int i = 0; i < steps; i++) {
            Direction dir = new Direction(i * dtheta * (float)Math.PI / 180);
            MapLocation potentialPos = p.add(dir, RobotType.GARDENER.bodyRadius*2 + GameConstants.GENERAL_SPAWN_OFFSET);
            // Only check for a radius of 0.9 to be able to handle really cramped maps
            if (onMap(potentialPos, 1f) && !rc.isCircleOccupied(potentialPos, 0.9f)) {
                cnt++;
            }
        }

        return cnt;
    }

    private static final CustomQueue queue = new CustomQueue();
    private static final CustomQueue secondaryQueue = new CustomQueue();
    private static final CustomQueue tertiaryQueue = new CustomQueue();

    private static int[] explored = null;
    private static int[] costs = null;
    private static int[] parents = null;
    private static int[] neighbourOffsets = null;
    private static int pathfindingIndex = 0;

    void resetPathfinding() {
        pathfindingIndex++;
    }

    void addPathfindingseed(MapLocation seed) {
        if (explored == null) {
            // This will take some time
            explored = new int[PATHFINDING_WORLD_WIDTH * PATHFINDING_WORLD_WIDTH];
            costs = new int[PATHFINDING_WORLD_WIDTH * PATHFINDING_WORLD_WIDTH];
            parents = new int[PATHFINDING_WORLD_WIDTH * PATHFINDING_WORLD_WIDTH];

            // Mark the borders of the world as always being explored to prevent
            // us from going out of bounds (they will never be traversable anyway)
            for (int i = 0; i < PATHFINDING_WORLD_WIDTH; i++) {
                explored[i * PATHFINDING_WORLD_WIDTH] = 1 << 30;
                explored[i * PATHFINDING_WORLD_WIDTH + PATHFINDING_WORLD_WIDTH - 1] = 1 << 30;
                explored[i] = 1 << 30;
                explored[(PATHFINDING_WORLD_WIDTH - 1) * PATHFINDING_WORLD_WIDTH + i] = 1 << 30;
            }

            neighbourOffsets = new int[4];
            for (int i = 0; i < 4; i++) {
                neighbourOffsets[i] = dy[i] * PATHFINDING_WORLD_WIDTH + dx[i];
            }
        }

        MapLocation relativePos = seed.translate(-explorationOrigin.x, -explorationOrigin.y);
        int seedx = (int)Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int seedy = (int)Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        int index = seedy * PATHFINDING_WORLD_WIDTH + seedx;
        explored[index] = pathfindingIndex;
        costs[index] = 0;
        queue.addLast(seedx + seedy * PATHFINDING_WORLD_WIDTH);
    }

    static int searchTime = 0;
    static int searchTime2 = 0;
    static int searchTime3 = 0;
    static int searchTime4 = 0;

    void addPathfindingSeeds() throws GameActionException {
        boolean addedAny = false;
        for (int i = 0; i < NUMBER_OF_TARGETS; ++i) {
            int offset = TARGET_OFFSET + 10 * i;
            int timeSpotted = rc.readBroadcast(offset);
            float priority = rc.readBroadcastFloat(offset + 1) / (rc.getRoundNum() - timeSpotted + 5.0f);
            System.out.println("Target number " + i);
            System.out.println("Time spotted: " + timeSpotted);
            System.out.println("Priority: " + priority);
            MapLocation loc = readBroadcastPosition(offset + 2);
            System.out.println("Location: " + loc);
            if (priority > 0.5 && timeSpotted > rc.getRoundNum() - 300) {
                addPathfindingseed(loc);
                addedAny = true;
            }
        }
        if (!addedAny) {
            for (MapLocation archon : initialArchonLocations) {
                addPathfindingseed(archon);
            }
        }
        /*MapLocation[] archons = rc.getInitialArchonLocations(rc.getTeam().opponent());
        int lastAttackingEnemySpotted = rc.readBroadcast(HIGH_PRIORITY_TARGET_OFFSET);
        MapLocation highPriorityTargetPos = readBroadcastPosition(HIGH_PRIORITY_TARGET_OFFSET + 1);
        if (rc.getRoundNum() < lastAttackingEnemySpotted + 50) {
            addPathfindingseed(highPriorityTargetPos);
        }
        else {
            for (MapLocation archon : archons) {
                addPathfindingseed(archon);
            }
        }*/
    }

    void pathfinding() throws GameActionException {
        if (explored == null) {
            // Pathfinding not started yet
            resetPathfinding();
            addPathfindingSeeds();
        }

        int w0 = Clock.getBytecodeNum();

        float centerOffsetX = explorationOrigin.x - (mapEdges0 + mapEdges2) * 0.5f;
        float centerOffsetY = explorationOrigin.y - (mapEdges1 + mapEdges3) * 0.5f;
        double mapRadius = Math.min(mapEdges0 - mapEdges2, mapEdges1 - mapEdges3) * 0.5f;
        mapRadius /= PATHFINDING_NODE_SIZE;
        centerOffsetX /= PATHFINDING_NODE_SIZE;
        centerOffsetY /= PATHFINDING_NODE_SIZE;

        int timeLimit = 5000;
        while (true) {
            if (Clock.getBytecodesLeft() < timeLimit) {
                searchTime += Clock.getBytecodeNum() - w0;
                return;
            }

            int w2 = Clock.getBytecodeNum();

            int node;
            // Inlined check for queue.isEmpty
            if (queue.head != queue.tail) {
                node = queue.pollFirst();
            } else if (secondaryQueue.head != secondaryQueue.tail) {
                node = secondaryQueue.pollFirst();
            } else if (tertiaryQueue.head != tertiaryQueue.tail) {
                node = tertiaryQueue.pollFirst();
            } else {
                break;
            }

            searchTime4 += Clock.getBytecodeNum() - w2;

            //rc.setIndicatorDot(origin.translate((x + 0.5f) * PATHFINDING_NODE_SIZE, (y + 0.5f) * PATHFINDING_NODE_SIZE), 255, 255, 255);

            for (int i = 0; i < 4; i++) {
                int nindex = node + neighbourOffsets[i];

                if (explored[nindex] < pathfindingIndex) {
                    int w1 = Clock.getBytecodeNum();
                    int nx = nindex % PATHFINDING_WORLD_WIDTH;
                    int ny = nindex / PATHFINDING_WORLD_WIDTH;
                    int chunk = pathfindingChunkDataForNode(nx, ny);

                    // Traversable if this is == 0, blocked if it is == 1
                    int blocked = (chunk >> ((ny % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (nx % PATHFINDING_CHUNK_SIZE))) & 1;
                    // Note that fullyExplored is stored in bit 31, not 30 so this
                    // will be 2 if it is explored and 0 if it is not explored
                    int fullyExplored = chunk >>> 30;
                    explored[nindex] = pathfindingIndex;
                    parents[nindex] = i;
                    searchTime2 += Clock.getBytecodeNum() - w1;

                    switch (blocked | fullyExplored) {
                        // blocked
                        case 1:
                        case 3:
                            // We definitely know it is not traversable
                            tertiaryQueue.addLast(nindex);
                            costs[nindex] = costs[node] + 100;
                            break;

                        // fullyExplored == 1
                        case 2:
                            // We definitely know it is traversable
                            queue.addLast(nindex);
                            costs[nindex] = costs[node] + 1;
                            break;

                        // traversable, but not fully explored. It might actually be blocked
                        default:
                            w1 = Clock.getBytecodeNum();

                            // It may be traversable or it may not, we don't really know

                            // Short circuit the check below for a large part of the map
                            if (Math.hypot(nx + centerOffsetX, ny + centerOffsetY) < mapRadius) {
                                queue.addLast(nindex);
                                costs[nindex] = costs[node] + 1;
                            } else {
                                float wx = explorationOrigin.x + nx * PATHFINDING_NODE_SIZE;
                                float wy = explorationOrigin.y + ny * PATHFINDING_NODE_SIZE;
                                // Inlined onMap call
                                if (wx <= mapEdges0 && wy <= mapEdges1 && wx >= mapEdges2 && wy >= mapEdges3) {
                                    queue.addLast(nindex);
                                    costs[nindex] = costs[node] + 1;
                                }
                            }

                            searchTime3 += Clock.getBytecodeNum() - w1;
                            break;
                    }

                    //rc.debug_setIndicatorDot(origin.translate(nx * PATHFINDING_NODE_SIZE, ny * PATHFINDING_NODE_SIZE), 0, 0, 200);
                }
            }
        }

        searchTime += Clock.getBytecodeNum() - w0;
        //System.out.println("SEARCH TIME: " + searchTime + "\t" + searchTime2 + "\t" + searchTime3 + "\t" + searchTime4);
        searchTime = 0;
        searchTime2 = 0;
        searchTime3 = 0;
        searchTime4 = 0;
        //yieldAndDoBackgroundTasks();
        //debug_graph();
        //yieldAndDoBackgroundTasks();
        //debug_search();

        // Write pathfinding result
        broadcastPathfindingResult();

        {
            resetPathfinding();
            addPathfindingSeeds();
        }
    }

    void broadcastPathfindingResult() throws GameActionException {
        assert (PATHFINDING_CHUNK_SIZE == 4);

        for (int cy = 0; cy < PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE; cy++) {
            float wy = explorationOrigin.y + (cy + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE;

            // Don't bother broadcasting information for tiles outside the map
            if (!onMapY(wy, -PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE * 0.5f)) continue;

            for (int cx = 0; cx < PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE; cx++) {
                float wx = explorationOrigin.x + (cx + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE;

                if (!onMapX(wx, -PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE * 0.5f)) continue;

                int data = 0;
                // Iterate over all nodes in the chunk
                int offset = cy * PATHFINDING_CHUNK_SIZE * PATHFINDING_WORLD_WIDTH + cx * PATHFINDING_CHUNK_SIZE;
                for (int dy = 0; dy < PATHFINDING_CHUNK_SIZE; dy++) {
                    int o = offset + dy * PATHFINDING_WORLD_WIDTH;
                    int shift = 2 * dy * PATHFINDING_CHUNK_SIZE;
                    // Loop unrolled 4x
                    data |= (parents[o] << shift) | (parents[o + 1] << (shift + 2)) | (parents[o + 2] << (shift + 4)) | (parents[o + 3] << (shift + 6));
                }

                rc.broadcast(PATHFINDING_RESULT_TO_ENEMY_ARCHON + cy * (PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE) + cx, data);
            }
        }
    }

    void debug_graph() throws GameActionException {
        for (int y = 0; y < PATHFINDING_WORLD_WIDTH; y++) {
            for (int x = 0; x < PATHFINDING_WORLD_WIDTH; x++) {
                int index = y * PATHFINDING_WORLD_WIDTH + x;
                if (explored[index] == pathfindingIndex) {
                    MapLocation loc = explorationOrigin.translate((x + 0.5f) * PATHFINDING_NODE_SIZE, (y + 0.5f) * PATHFINDING_NODE_SIZE);
                    int chunk = pathfindingChunkDataForNode(x, y);
                    boolean blocked = ((chunk >> ((y % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (x % PATHFINDING_CHUNK_SIZE))) & 1) != 0;
                    boolean fullyExplored = (chunk >>> 31) != 0;
                    if (blocked) {
                        rc.setIndicatorDot(loc, 255, 255, 255);
                    } else if (!fullyExplored) {
                        rc.setIndicatorDot(loc, 128, 128, 128);
                    } else {
                        rc.setIndicatorDot(loc, 0, 0, 0);
                    }
                }
            }
        }
    }

    void debug_search() {
        for (int y = 0; y < PATHFINDING_WORLD_WIDTH; y++) {
            for (int x = 0; x < PATHFINDING_WORLD_WIDTH; x++) {
                int index = y * PATHFINDING_WORLD_WIDTH + x;
                if (explored[index] == pathfindingIndex) {
                    MapLocation loc = explorationOrigin.translate((x + 0.5f) * PATHFINDING_NODE_SIZE, (y + 0.5f) * PATHFINDING_NODE_SIZE);
                    debug_setIndicatorDot(loc, costs[index] / 120f);
                }
            }
        }
    }

    void debug_resign() {
        // Give up if the odds do not seem to be in our favor
        if (rc.getRoundNum() > 1800 && rc.getRobotCount() <= 2 && rc.senseNearbyRobots(-1, enemy).length >= 4 && rc.getTeamBullets() < 200 && rc.getTreeCount() <= 1) {
            System.out.println("RESIGNING");
            rc.resign();
        }
    }
}
