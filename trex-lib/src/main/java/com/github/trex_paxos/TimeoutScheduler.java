package com.github.trex_paxos;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.trex_paxos.TrexLogger.LOGGER;

/**
 * Handles scheduling of timeouts and heartbeats for a Paxos node using virtual threads.
 * Randomizes timeout intervals to prevent thundering herds while maintaining
 * reasonably tight bounds.
 */

public class TimeoutScheduler {
  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(150);
  private static final Duration LONG_TIMEOUT = Duration.ofMillis(300);
  private static final Duration HEARTBEAT = Duration.ofMillis(50);

  private final short nodeId;
  private volatile Thread timeoutThread;
  private volatile Thread heartbeatThread;
  private final AtomicBoolean running = new AtomicBoolean(true);

  public TimeoutScheduler(short nodeId) {
    this.nodeId = nodeId;
  }

  public void setTimeout(Runnable task) {
    clearTimeout();
    long delay = ThreadLocalRandom.current().nextLong(
        SHORT_TIMEOUT.toMillis(),
        LONG_TIMEOUT.toMillis()
    );
    LOGGER.fine(() -> "Node " + nodeId + " setting timeout for " + delay + "ms");
    timeoutThread = Thread.ofVirtual()
        .name("timeout-" + nodeId)
        .start(() -> {
          try {
            Thread.sleep(delay);
            if (running.get()) {
              task.run();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  public void clearTimeout() {
    var current = timeoutThread;
    if (current != null) {
      LOGGER.fine(() -> "Node " + nodeId + " clearing timeout");
      current.interrupt();
      timeoutThread = null;
    }
  }

  public void setHeartbeat(Runnable task) {
    if (heartbeatThread != null && heartbeatThread.isAlive()) {
      return;
    }

    LOGGER.fine(() -> "Node " + nodeId + " setting heartbeat");
    heartbeatThread = Thread.ofVirtual()
        .name("heartbeat-" + nodeId)
        .start(() -> {
          try {
            while (running.get()) {
              long start = System.nanoTime();
              task.run();
              long elapsed = System.nanoTime() - start;
              long sleepTime = HEARTBEAT.toNanos() - elapsed;
              if (sleepTime > 0) {
                //noinspection BusyWait
                Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  public void close() {
    running.set(false);
    clearTimeout();
    if (heartbeatThread != null) {
      heartbeatThread.interrupt();
      heartbeatThread = null;
    }
  }
}

