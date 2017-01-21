package bot;

import battlecode.common.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;

class Archon extends Robot {

    @Override
    public void run() throws GameActionException {
        rc.broadcast(GARDENER_OFFSET, -1000);
        rc.broadcast(HIGH_PRIORITY_TARGET_OFFSET, -1000);
        int STOP_SPENDING_AT_TIME = 100;

        // Set the exploration origin if it has not been set already
        if (readBroadcastPosition(EXPLORATION_ORIGIN).equals(new MapLocation(0, 0))) {
            System.out.println("Set exploration origin");
            broadcast(EXPLORATION_ORIGIN, rc.getLocation());

            for (int i = 0; i < 4; i++) {
                rc.broadcastFloat(MAP_EDGE_BROADCAST_OFFSET + i + 1, mapEdges[i]);
            }
        }

        System.out.println("I'm an archon! ");

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

    private static ArrayDeque<Integer> queue = new ArrayDeque<>();
    private static ArrayDeque<Integer> secondaryQueue = new ArrayDeque<>();
    private static ArrayDeque<Integer> tertiaryQueue = new ArrayDeque<>();

    private static int[] explored = null;
    private static int[] costs = null;
    private static int[] parents = null;
    private static int[] neighbourOffsets = null;
    private static boolean[][] differentChunk = null;
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

            differentChunk = new boolean[PATHFINDING_CHUNK_SIZE * PATHFINDING_CHUNK_SIZE][];
            for (int x = 0; x < PATHFINDING_CHUNK_SIZE; x++) {
                for (int y = 0; y < PATHFINDING_CHUNK_SIZE; y++) {
                    int index = y * PATHFINDING_CHUNK_SIZE + x;
                    differentChunk[index] = new boolean[4];
                    for (int i = 0; i < 4; i++) {
                        int nx = x + dx[i];
                        int ny = y + dy[i];
                        if (nx < 0 || ny < 0 || nx >= PATHFINDING_CHUNK_SIZE || ny >= PATHFINDING_CHUNK_SIZE) {
                            differentChunk[index][i] = true;
                        }
                    }
                }
            }
        }

        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN).translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);
        MapLocation relativePos = seed.translate(-origin.x, -origin.y);
        int seedx = (int) Math.floor(relativePos.x / PATHFINDING_NODE_SIZE);
        int seedy = (int) Math.floor(relativePos.y / PATHFINDING_NODE_SIZE);
        int index = seedy * PATHFINDING_WORLD_WIDTH + seedx;
        explored[index] = pathfindingIndex;
        costs[index] = 0;
        queue.add(seedx + seedy * PATHFINDING_WORLD_WIDTH);
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

        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN).translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);

        int w0 = Clock.getBytecodeNum();

        while (queue.size() > 0 || secondaryQueue.size() > 0 || tertiaryQueue.size() > 0) {
            if (Clock.getBytecodesLeft() < 1600) {
                searchTime += Clock.getBytecodeNum() - w0;
                return;
            }

            int node;
            if (queue.size() > 0) {
                node = queue.pollFirst();
            } else if (secondaryQueue.size() > 0) {
                node = secondaryQueue.pollFirst();
            } else {
                node = tertiaryQueue.pollFirst();
            }

            int x = node % PATHFINDING_WORLD_WIDTH;
            int y = node / PATHFINDING_WORLD_WIDTH;

            int w2 = Clock.getBytecodeNum();
            rc.setIndicatorDot(origin.translate(x * PATHFINDING_NODE_SIZE, y * PATHFINDING_NODE_SIZE), 255, 255, 255);
            searchTime4 += Clock.getBytecodeNum() - w2;

            for (int i = 0; i < 4; i++) {
                int nindex = node + neighbourOffsets[i];

                if (explored[nindex] < pathfindingIndex) {
                    int w1 = Clock.getBytecodeNum();
                    int nx = nindex % PATHFINDING_WORLD_WIDTH;
                    int ny = nindex / PATHFINDING_WORLD_WIDTH;
                    int chunk = pathfindingChunkDataForNode(nx, ny);
                    boolean traversable = ((chunk >> ((ny % PATHFINDING_CHUNK_SIZE) * PATHFINDING_CHUNK_SIZE + (nx % PATHFINDING_CHUNK_SIZE))) & 1) == 0;
                    boolean fullyExplored = (chunk & (1 << 30)) != 0;
                    explored[nindex] = pathfindingIndex;
                    parents[nindex] = i;
                    int ncost = costs[node];
                    searchTime2 += Clock.getBytecodeNum() - w1;

                    if (!traversable) {
                        // We definitely know it is not traversable
                        tertiaryQueue.addLast(nindex);
                        ncost += 100;
                    } else if (fullyExplored) {
                        // We definitely know it is traversable
                        queue.add(nindex);
                        ncost += 1;
                    } else {
                        w1 = Clock.getBytecodeNum();

                        // It may be traversable or it may not, we don't really know
                        if (onMap(origin.x + x * PATHFINDING_NODE_SIZE, origin.y + y * PATHFINDING_NODE_SIZE)) {
                            queue.addLast(nindex);
                            ncost += 1;
                        } else {
                            ncost += 1000;
                        }

                        searchTime3 += Clock.getBytecodeNum() - w1;
                    }

                    costs[nindex] = ncost;
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

        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN).translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);

        assert (PATHFINDING_CHUNK_SIZE == 4);

        for (int cy = 0; cy < PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE; cy++) {
            float wy = origin.y + (cy + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE;

            // Don't bother broadcasting information for tiles outside the map
            if (!onMapY(wy, -PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE * 0.5f)) continue;

            for (int cx = 0; cx < PATHFINDING_WORLD_WIDTH / PATHFINDING_CHUNK_SIZE; cx++) {
                float wx = origin.x + (cx + 0.5f) * PATHFINDING_CHUNK_SIZE * PATHFINDING_NODE_SIZE;

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

    void debug_search() throws GameActionException {
        MapLocation origin = readBroadcastPosition(EXPLORATION_ORIGIN).translate(-PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2, -PATHFINDING_NODE_SIZE * PATHFINDING_WORLD_WIDTH / 2);

        for (int y = 0; y < PATHFINDING_WORLD_WIDTH; y++) {
            for (int x = 0; x < PATHFINDING_WORLD_WIDTH; x++) {
                int index = y * PATHFINDING_WORLD_WIDTH + x;
                if (explored[index] == pathfindingIndex) {
                    MapLocation loc = origin.translate(x * PATHFINDING_NODE_SIZE, y * PATHFINDING_NODE_SIZE);
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
