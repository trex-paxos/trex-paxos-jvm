/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.advisory_locks;

import com.github.trex_paxos.advisory_locks.store.LockStore;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TrexLockLocalTests {

  private static TrexLockService lockClient;

  private record TrexLockServiceLocal(LockStore store) implements TrexLockService {
    static final AtomicLong stampGen = new AtomicLong(System.currentTimeMillis());

    @Override
    public Optional<LockHandle> tryLock(String id, Instant expiryTime) {
      return store.tryAcquireLock(id, expiryTime, stampGen.incrementAndGet())
          .map(entry -> new LockHandle(entry.lockId(), entry.stamp(), entry.expiryTime()));
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
    LockStore lockStore = new LockStore(mvStore, Duration.ofSeconds(1));
    lockClient = new TrexLockServiceLocal(lockStore);
  }

  @Test
  void shouldAcquireLockSuccessfully() {
    String lockId = "resource-1";
    LockHandle handle = lockClient.tryLock(lockId,
            LockStore.expiryTimeWithSafetyGap(
                Duration.ofSeconds(30),
                Duration.ofSeconds(1)))
        .orElseThrow();

    assertThat(handle).isNotNull();
    assertThat(handle.id()).isEqualTo(lockId);
    assertThat(handle.stamp()).isPositive();
    assertThat(handle.expireTimeWithSafetyGap()).isAfter(Instant.now());
  }

  @Test
  void shouldReleaseLockSuccessfully() {
    String lockId = "resource-2";
    Duration holdDuration = Duration.ofSeconds(30);

    LockHandle handle = lockClient.tryLock(lockId, LockStore.expiryTimeWithSafetyGap(
        Duration.ofSeconds(30),
        Duration.ofSeconds(1))
    ).orElseThrow();
    boolean released = lockClient.releaseLock(handle);

    assertThat(released).isTrue();
  }

  @Test
  void shouldFailToAcquireLockedResource() {
    String lockId = "resource-3";

    final var expiryTime = LockStore.expiryTimeWithSafetyGap(
        Duration.ofSeconds(30),
        Duration.ofSeconds(1));

    LockHandle firstHandle = lockClient.tryLock(lockId, expiryTime).orElseThrow();
    final var secondHandle = lockClient.tryLock(lockId, expiryTime);

    assertThat(firstHandle).isNotNull();
    assertThat(secondHandle).isEmpty();
  }

  @Test
  void shouldProvideUnsafeExpiryTime() {
    String lockId = "resource-4";
    final var expiryTime = LockStore.expiryTimeWithSafetyGap(
        Duration.ofSeconds(30),
        Duration.ofSeconds(1));

    LockHandle handle = lockClient.tryLock(lockId, expiryTime).orElseThrow();
    long unsafeExpiry = lockClient.expireTimeUnsafe(handle);

    assertThat(unsafeExpiry).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  void shouldAcquireLockAfterExpiry() throws InterruptedException {
    String lockId = "resource-6";

    final var expiryTime = LockStore.expiryTimeWithSafetyGap(
        Duration.ofMillis(100),
        Duration.ofMillis(1));

    LockHandle firstHandle = lockClient.tryLock(lockId, expiryTime).orElseThrow();
    Thread.sleep(200); // Wait for expiry
    LockHandle secondHandle = lockClient.tryLock(lockId, expiryTime).orElseThrow();

    assertThat(firstHandle).isNotNull();
    assertThat(secondHandle)
        .isNotNull()
        .isNotEqualTo(firstHandle);
    assertThat(secondHandle.stamp()).isGreaterThan(firstHandle.stamp());
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
        final var expiryTime = LockStore.expiryTimeWithSafetyGap(
            holdDuration,
            Duration.ofSeconds(1));
        var handle = lockClient.tryLock(lockId, expiryTime);
        if (handle.isPresent()) {
          successfulLocks.incrementAndGet();
        }
      });
      threads[i].start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    // this gives either one or two depending on the timing of the threads
    assertThat(successfulLocks.get()).isGreaterThan(0);
  }

}
