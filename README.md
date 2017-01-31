Battlecode Bot
===========================
Team: Arbitrary Graph Restoration Fund

## Overview

Run a game using

~~~
# to run two instances of the latest bot against each other
./run
# to run the specified version against the latest version
./run -a versionA
# to run the two specified versions against each other on a specific map
./run -a versionA -b versionB -m sparseforest
# to run a tournament where two versions battle each other in all maps
./run -a versionA -b versionB --tournament
~~~

New backup versions can be created using the `backup` script

~~~
./backup versionTag
~~~

Where `versionTag` is some identifier like `v1`

### Project structure

- `README.md`
    This file.
- `build.gradle`
    The Gradle build file used to build and run players.
- `src/`
    Player source code.
- `test/`
    Player test code.
- `client/`
    Contains the client.
- `build/`
    Contains compiled player code and other artifacts of the build process. Can be safely ignored.
- `matches/`
    The output folder for match files.
- `maps/`
    The default folder for custom maps.
- `gradlew`, `gradlew.bat`
    The Unix (OS X/Linux) and Windows versions, respectively, of the Gradle wrapper. These are nifty scripts that you can execute in a terminal to run the Gradle build tasks of this project. If you aren't planning to do command line development, these can be safely ignored.
- `gradle/`
    Contains files used by the Gradle wrapper scripts. Can be safely ignored.


