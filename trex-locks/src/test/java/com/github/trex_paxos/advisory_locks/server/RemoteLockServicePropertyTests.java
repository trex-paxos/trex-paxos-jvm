// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.advisory_locks.server;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Provide;
import org.h2.mvstore.MVStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class RemoteLockServicePropertyTests {

  enum LockState {
    ABSENT,    // Lock does not exist
    PRESENT    // Lock exists
  }

  enum StampRelation {
    LESS,     // Test stamp less than existing
    EQUAL,    // Test stamp equals existing
    GREATER   // Test stamp greater than existing
  }

  enum DurationRelation {
    LESS,     // newDuration < existingDuration
    EQUAL,    // newDuration = existingDuration
    GREATER   // newDuration > existingDuration
  }

  record TestCase(
      LockState lockState,
      StampRelation stampRelation,
      DurationRelation durationRelation,
      LockServerCommandValue command
  ) {
  }

  static final String TEST_LOCK_ID = "test-lock";
  static final long BASE_STAMP = 100L;
  static final Instant BASE_DURATION = Instant.now().plus(Duration.ofMinutes(30));

  //@Property(generation = GenerationMode.EXHAUSTIVE)
  void remoteServiceTests(
      //@ForAll("testCases")
      TestCase testCase) {
    MVStore store = MVStore.open(null);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    LockServerSimulation simulation = new LockServerSimulation(store, scheduler);

    // Make node 1 the leader
    final var engine1 = simulation.getEngine((byte) 1);
    var prepareResult = engine1.timeoutForTest();
    prepareResult.ifPresent(msg -> simulation.deliverMessages(List.of(msg)));

    // Calculate test stamp based on relation
    final long testStamp = switch (testCase.stampRelation()) {
      case LESS -> BASE_STAMP - 1;
      case EQUAL -> BASE_STAMP;
      case GREATER -> BASE_STAMP + 1;
    };

    // Calculate test duration based on relation
    final Instant testExpiryTime = switch (testCase.durationRelation()) {
      case LESS -> BASE_DURATION; // FIXME: DUNNO
      case EQUAL -> BASE_DURATION;
      case GREATER -> BASE_DURATION;  // FIXME: DUNNO
    };

    // Setup initial lock state if needed
    if (testCase.lockState() == LockState.PRESENT) {
      simulation.getStore((byte) 1)
          .tryAcquireLock(TEST_LOCK_ID, BASE_DURATION, BASE_STAMP);
    }

    // Create command with calculated parameters
    LockServerCommandValue command = switch (testCase.command()) {
      case LockServerCommandValue.TryAcquireLock _ ->
          new LockServerCommandValue.TryAcquireLock(TEST_LOCK_ID, testExpiryTime);
      case LockServerCommandValue.ReleaseLock _ -> new LockServerCommandValue.ReleaseLock(TEST_LOCK_ID, testStamp);
      case LockServerCommandValue.GetLock _ -> new LockServerCommandValue.GetLock(TEST_LOCK_ID);
    };

    CompletableFuture<LockServerReturnValue> future = new CompletableFuture<>();
    simulation.getServer((byte) 1).processCommand(command, future);
    LockServerReturnValue result = future.join();

    // Record state after command execution
    final var now = Instant.now();
    final var store1 = simulation.getStore((byte) 1);
    final var store2 = simulation.getStore((byte) 2);

    verifyResult(testCase, result, now);

    // Verify both nodes have consistent state
    assert store1.getLock(TEST_LOCK_ID).equals(store2.getLock(TEST_LOCK_ID));

    scheduler.shutdown();
  }

  private void verifyResult(TestCase testCase, LockServerReturnValue result, Instant verificationTime) {
    switch (result) {
      case LockServerReturnValue.TryAcquireLockReturn tryResult -> {
        if (testCase.lockState() == LockState.ABSENT) {
          assert tryResult.result().isPresent();
          assert tryResult.result().get().lockId().equals(TEST_LOCK_ID);
          var lock = tryResult.result().get();
          assert !lock.expiryTime().isBefore(verificationTime);

          // TODO check the expiry time is within a reasonable range

        } else {
          // If lock exists, should only succeed if existing lock is expired
          if (tryResult.result().isPresent()) {
            var lock = tryResult.result().get();
            assert lock.expiryTime().isBefore(verificationTime);
          }
        }
      }
      case LockServerReturnValue.ReleaseLockReturn releaseResult -> {
        if (testCase.lockState() == LockState.PRESENT) {
          // Release only succeeds with matching stamp on non-expired lock
          assert releaseResult.result() == (testCase.stampRelation() == StampRelation.EQUAL);
        } else {
          assert !releaseResult.result();
        }
      }
      case LockServerReturnValue.GetLockReturn getResult -> {
        if (testCase.lockState() == LockState.ABSENT) {
          assert getResult.result().isEmpty();
        } else {
          assert getResult.result().isPresent();
          var lock = getResult.result().get();
          assert lock.lockId().equals(TEST_LOCK_ID);
          assert lock.stamp() == BASE_STAMP;

          // TODO check the expiry time is within a reasonable range
        }
      }
    }
  }

  @SuppressWarnings("unused")
  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(LockState.values()),
        Arbitraries.of(StampRelation.values()),
        Arbitraries.of(DurationRelation.values()),
        Arbitraries.of(
            new LockServerCommandValue.TryAcquireLock(TEST_LOCK_ID, BASE_DURATION),
            new LockServerCommandValue.ReleaseLock(TEST_LOCK_ID, BASE_STAMP),
            new LockServerCommandValue.GetLock(TEST_LOCK_ID)
        )
    ).as(TestCase::new);
  }
}
