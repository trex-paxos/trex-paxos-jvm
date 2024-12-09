package com.github.trex_paxos.advisory_locks;

import com.github.trex_paxos.UUIDGenerator;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TrexLockLocalTests {

  private static TrexLockClient lockClient;

  private static class TrexLockClientLocal implements TrexLockClient {
    private final LockStore store;

    TrexLockClientLocal(LockStore store) {
      this.store = store;
    }

    @Override
    public LockHandle tryLock(String id, Duration durationToHoldLock) {
      if (durationToHoldLock.isNegative()) {
        throw new IllegalArgumentException("Duration cannot be negative");
      }
      if (durationToHoldLock.isZero()) {
        return null;
      }

      return store.tryAcquireLock(id, UUIDGenerator.generateUUID().getMostSignificantBits(), durationToHoldLock)
          .map(entry -> new LockHandle(entry.lockId(), entry.stamp(), entry.expiryTime()))
          .orElse(null);
    }

    @Override
    public long expireTimeUnsafe(LockHandle lock) {
      return store.getLock(lock.id())
          .map(entry -> entry.expiryTime().toEpochMilli())
          .orElse(0L);
    }

    @Override
    public Instant expireTimeWithSafetyGap(LockHandle lock, Duration safetyGap) {
      return store.getLock(lock.id())
          .map(entry -> entry.expiryTime().plus(safetyGap))
          .orElse(Instant.EPOCH);
    }

    @Override
    public boolean releaseLock(LockHandle lock) {
      return store.releaseLock(lock.id(), lock.stamp());
    }
  }

  @BeforeAll
  static void setup() {
    MVStore mvStore = MVStore.open(null); // in-memory store
    LockStore lockStore = new LockStore(mvStore);
    lockClient = new TrexLockClientLocal(lockStore);
  }

  @Test
  void shouldAcquireLockSuccessfully() {
    String lockId = "resource-1";
    Duration holdDuration = Duration.ofSeconds(30);

    LockHandle handle = lockClient.tryLock(lockId, holdDuration);

    assertThat(handle).isNotNull();
    assertThat(handle.id()).isEqualTo(lockId);
    assertThat(handle.stamp()).isPositive();
    assertThat(handle.expireTimeWithSafetyGap()).isAfter(Instant.now());
  }

  @Test
  void shouldReleaseLockSuccessfully() {
    String lockId = "resource-2";
    Duration holdDuration = Duration.ofSeconds(30);

    LockHandle handle = lockClient.tryLock(lockId, holdDuration);
    boolean released = lockClient.releaseLock(handle);

    assertThat(released).isTrue();
  }

  @Test
  void shouldFailToAcquireLockedResource() {
    String lockId = "resource-3";
    Duration holdDuration = Duration.ofSeconds(30);

    LockHandle firstHandle = lockClient.tryLock(lockId, holdDuration);
    LockHandle secondHandle = lockClient.tryLock(lockId, holdDuration);

    assertThat(firstHandle).isNotNull();
    assertThat(secondHandle).isNull();
  }

  @Test
  void shouldProvideUnsafeExpiryTime() {
    String lockId = "resource-4";
    Duration holdDuration = Duration.ofSeconds(30);

    LockHandle handle = lockClient.tryLock(lockId, holdDuration);
    long unsafeExpiry = lockClient.expireTimeUnsafe(handle);

    assertThat(unsafeExpiry).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  void shouldProvideSafeExpiryTimeWithGap() {
    String lockId = "resource-5";
    Duration holdDuration = Duration.ofSeconds(30);
    Duration safetyGap = Duration.ofMinutes(5);

    LockHandle handle = lockClient.tryLock(lockId, holdDuration);
    Instant safeExpiry = lockClient.expireTimeWithSafetyGap(handle, safetyGap);

    assertThat(safeExpiry)
        .isAfter(Instant.ofEpochMilli(lockClient.expireTimeUnsafe(handle)))
        .isAfter(Instant.now().plus(safetyGap));
  }

  @Test
  void shouldAcquireLockAfterExpiry() throws InterruptedException {
    String lockId = "resource-6";
    Duration shortDuration = Duration.ofMillis(100);

    LockHandle firstHandle = lockClient.tryLock(lockId, shortDuration);
    Thread.sleep(200); // Wait for expiry
    LockHandle secondHandle = lockClient.tryLock(lockId, shortDuration);

    assertThat(firstHandle).isNotNull();
    assertThat(secondHandle)
        .isNotNull()
        .isNotEqualTo(firstHandle);
    assertThat(secondHandle.stamp()).isGreaterThan(firstHandle.stamp());
  }

  @Test
  void shouldHandleZeroDurationLock() {
    String lockId = "resource-7";
    Duration zeroDuration = Duration.ZERO;

    LockHandle handle = lockClient.tryLock(lockId, zeroDuration);

    assertThat(handle).isNull();
  }

  @Test
  void shouldHandleNegativeDurationLock() {
    String lockId = "resource-8";
    Duration negativeDuration = Duration.ofSeconds(-1);

    assertThatThrownBy(() -> lockClient.tryLock(lockId, negativeDuration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldHandleInvalidLockRelease() {
    String lockId = "resource-9";
    LockHandle invalidHandle = new LockHandle(lockId, -1L, Instant.now());

    boolean released = lockClient.releaseLock(invalidHandle);

    assertThat(released).isFalse();
  }

  @Test
  void shouldHandleConcurrentLockAttempts() throws InterruptedException {
    String lockId = "resource-10";
    Duration holdDuration = Duration.ofSeconds(30);
    int threadCount = 10;

    var successfulLocks = new java.util.concurrent.atomic.AtomicInteger(0);
    var threads = new Thread[threadCount];

    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        LockHandle handle = lockClient.tryLock(lockId, holdDuration);
        if (handle != null) {
          successfulLocks.incrementAndGet();
        }
      });
      threads[i].start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    assertThat(successfulLocks.get()).isEqualTo(1);
  }

}
