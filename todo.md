##High priority
- Build trees in such a way that new gardeners can heal trees that have lost their "original gardener".
- Add position scoring (including bullet avoidance) to all units.
- Broadcast information about trees with units in them, so that lumberjacks can come and chop them down.

##Medium priority
- Listen to the opponent's broadcasts.
- Make units with low health more likely to avoid combat.
- Prefer shooting at opponents with low health.
- Remove bullets from nearbyBullets if they don't intersect the circle of radius 3.5f (speed + size) around our current position.

##Low priority
- Add bonus to positions from which we can shoot at opponents.
- Add bonus to positions where the opponent does not want to shoot at us (because their units are behind us)
- Decide whether it's worth shooting at a target, depending on what we hit if the target avoids the bullet
- Try to figure out in what order different units are processed. In general, scouts want to chase opponent scouts if they have the advantage of being processed later than the opponent scout.
- Only build tanks if we can get them to the opponent. They aren't very useful as defenders.
- Use Archons as scouts (to discover stuff).
- Use Archons to block hostile units so that they cannot move.
