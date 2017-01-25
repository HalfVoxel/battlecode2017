package bot;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;

public class FirePlan {
    final Direction direction;
    final int bulletNumber;

    public FirePlan(Direction direction, int bulletNumber) {
        this.direction = direction;
        this.bulletNumber = bulletNumber;
    }

    public void apply(RobotController rc) throws GameActionException {
        if (bulletNumber == 1) {
            rc.fireSingleShot(direction);
        } else if (bulletNumber == 3) {
            rc.fireTriadShot(direction);
        } else if (bulletNumber == 5) {
            rc.firePentadShot(direction);
        }
    }
}
