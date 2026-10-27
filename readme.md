# AtomicClock
![Java](https://img.shields.io/badge/Java-1.6-ED8B00?logo=openjdk&logoColor=white)
[![Maven Central](https://img.shields.io/maven-central/v/com.ydo4ki.games/AtomicClock?label=Maven%20Central)](https://central.sonatype.com/artifact/com.ydo4ki.games/AtomicClock)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A lightweight yet powerful update thread for your game loop that supports tasks scheduling.

From `ydo4ki.games` framework used in Horiforge and (as it was originally made for) some builds of Interood.

### How to use:

The usage is very simple and straightforward:

```java
import com.ydo4ki.games.time.AtomicClock;

public class MyGame {
    public static void main(String[] args) {
        int target_fps = 60;
        int target_tps = 15;
        AtomicClock renderLoop = new AtomicClock(target_fps, MyGame::render, AtomicClock.CatchUpMode.CATCH_UP_ONLY_SHORT_DELAYS, "render", false);
        AtomicClock updateLoop = new AtomicClock(target_tps, MyGame::update, AtomicClock.CatchUpMode.CATCH_UP_ONLY_SHORT_DELAYS, "update", true);

        // init your game

        renderLoop.start();
        updateLoop.start();

        // congrats, it is running now!
        // make sure that at least one of the clocks is not daemon, otherwise the application will close here
        
        // for task scheduling use:
        updateLoop.in(60).thenRun(() -> {
            System.out.println("60 ticks has passed");
        });
        // or, if you want to specify time in milliseconds:
        updateLoop.inMillis(1000).thenRun(() -> {
            System.out.println("It's getting boring, it's been one second already");
        });
    }

    public static void render(long frame) {
        // game rendering here
    }

    public static void update(long tick) {
        // game update logic here
    }
}
```

## Installation

### Maven

```xml
<dependency>
    <groupId>com.ydo4ki.games</groupId>
    <artifactId>AtomicClock</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.ydo4ki.games:AtomicClock:1.0.0'
```

### No build system
```
Go to the releases tab and download latest jar
```

## How to build
1. Clone this repository
```bash
git clone https://github.com/Ydo4ki/AtomicClock.git
cd AtomicClock
```
2. Run maven build (maven should run on Java 8):
```bash
mvn clean package
```
