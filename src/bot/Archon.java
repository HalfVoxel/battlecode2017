package bot;

import battlecode.common.*;

class Archon extends Robot {

    @Override
    public void run() throws GameActionException {
        rc.broadcast(GARDENER_OFFSET, -1000);
        rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        int STOP_SPENDING_AT_TIME = 100;

        // Set the exploration origin if it has not been set already
        if (explorationOrigin.equals(new MapLocation(0, 0))) {
            System.out.println("Set exploration origin");
            explorationOrigin = rc.getLocation().translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);
            broadcast(EXPLORATION_ORIGIN, explorationOrigin);

            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (0 + 1), mapEdges0);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (1 + 1), mapEdges1);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (2 + 1), mapEdges2);
            rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + (3 + 1), mapEdges3);
        }

        System.out.println("I'm an archon! ");
        rc.broadcast(RobotType.ARCHON.ordinal(), rc.getInitialArchonLocations(rc.getTeam()).length);

        // The code you want your robot to perform every round should be in this loop
        while (true) {
            int gardenerCount = spawnedCount(RobotType.GARDENER);
            boolean saveForTank = false;
            int tankCount = spawnedCount(RobotType.TANK);
            int turnsLeft = rc.getRoundLimit() - rc.getRoundNum();

            if (rc.getTreeCount() > tankCount * 4 + 400 && rc.getTeamBullets() <= RobotType.TANK.bulletCost + 100 && gardenerCount > 1) {
                saveForTank = true;
            }

            boolean gardenersSeemToBeBlocked = rc.readBroadcast(GARDENER_CAN_PROBABLY_BUILD) > gardenerCount * 20 + 10;
            if ((gardenersSeemToBeBlocked || gardenerCount < 1 || rc.getTreeCount() > 6 * gardenerCount || rc.getTeamBullets() > RobotType.TANK.bulletCost + 100) && !saveForTank) {
                // Generate a random direction
                Direction dir = randomDirection();
                if (rc.canHireGardener(dir) && turnsLeft > STOP_SPENDING_AT_TIME) {
                    rc.hireGardener(dir);
                    rc.broadcast(RobotType.GARDENER.ordinal(), gardenerCount + 1);
                    if (gardenersSeemToBeBlocked) {
                        System.out.println("Hired gardener because all the existing ones seem to be blocked");
                    }

                    rc.broadcast(GARDENER_CAN_PROBABLY_BUILD, 0);
                }
            }

            BulletInfo[] bullets = rc.senseNearbyBullets(info.strideRadius + info.bodyRadius + 3f);
            RobotInfo[] units = rc.senseNearbyRobots();
            moveToAvoidBullets(rc.getLocation(), bullets, units);
            pathfinding();

            yieldAndDoBackgroundTasks();

            debug_resign();
        }
    }

    private static CustomQueue queue = new CustomQueue();
    private static CustomQueue secondaryQueue = new CustomQueue();
    private static CustomQueue tertiaryQueue = new CustomQueue();

    private static int[] explored = null;
    private static int[] costs = null;
    private static int[] parents = null;
    private static int[] neighbourOffsets = null;
    private static int pathfindingIndex = 0;

    void resetPathfinding() {
        pathfindingIndex++;
    }

    void addPathfindingseed(MapLocation seed) throws GameActionException {
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
        int seedx = (int) Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int seedy = (int) Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        int index = seedy * PATHFINDING_WORLD_WIDTH + seedx;
        explored[index] = pathfindingIndex;
        costs[index] = 0;
        queue.addLast(seedx + seedy * PATHFINDING_WORLD_WIDTH);
    }

    static int searchTime = 0;
    static int searchTime2 = 0;
    static int searchTime3 = 0;
    static int searchTime4 = 0;

    void pathfinding() throws GameActionException {
        if (explored == null) {
            // Pathfinding not started yet
            resetPathfinding();
            MapLocation[] archons = rc.getInitialArchonLocations(rc.getTeam().opponent());
            for (MapLocation archon : archons) {
                addPathfindingseed(archon);
            }
        }

        int w0 = Clock.getBytecodeNum();

        float centerOffsetX = explorationOrigin.x - (mapEdges0 + mapEdges2) * 0.5f;
        float centerOffsetY = explorationOrigin.y - (mapEdges1 + mapEdges3) * 0.5f;
        double mapRadius = Math.min(mapEdges0 - mapEdges2, mapEdges1 - mapEdges3) * 0.5f;
        mapRadius /= PATHFINDING_NODE_SIZE;
        centerOffsetX /= PATHFINDING_NODE_SIZE;
        centerOffsetY /= PATHFINDING_NODE_SIZE;

        while (true) {
            if (Clock.getBytecodesLeft() < 1600) {
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

            int x = node % PATHFINDING_WORLD_WIDTH;
            int y = node / PATHFINDING_WORLD_WIDTH;

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

                    switch(blocked | fullyExplored) {
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
        System.out.println("SEARCH TIME: " + searchTime + "\t" + searchTime2 + "\t" + searchTime3 + "\t" + searchTime4);
        searchTime = 0;
        searchTime2 = 0;
        searchTime3 = 0;
        searchTime4 = 0;
        yieldAndDoBackgroundTasks();
        debug_graph();
        yieldAndDoBackgroundTasks();
        debug_search();

        // Write pathfinding result
        broadcastPathfindingResult();

        {
            resetPathfinding();
            MapLocation[] archons = rc.getInitialArchonLocations(rc.getTeam().opponent());
            for (MapLocation archon : archons) {
                addPathfindingseed(archon);
            }
        }
    }

    void broadcastPathfindingResult() throws GameActionException {
        int w1 = Clock.getBytecodesLeft();
        int t1 = rc.getRoundNum();

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

        int t = (rc.getRoundNum() - t1) * 20000 + w1 - Clock.getBytecodesLeft();
        System.out.println("Broadcasting direction info took " + t);
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

    void debug_search() throws GameActionException {
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
        if (rc.getRoundNum() > 1800 && rc.getRobotCount() <= 2 && rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length >= 4 && rc.getTeamBullets() < 200 && rc.getTreeCount() <= 1) {
            System.out.println("RESIGNING");
            rc.resign();
        }
    }
}
