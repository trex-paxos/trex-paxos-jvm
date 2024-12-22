package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.advisory_locks.store.LockStore;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TrexRemoteLockServiceTests {
  private static final Logger LOGGER = Logger.getLogger(TrexRemoteLockServiceTests.class.getName());

  private MVStore store;
  private LockServerSimulation simulation;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    store = MVStore.open(null);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    simulation = new LockServerSimulation(store, scheduler);

    // Make node 1 the leader
    final var engine1 = simulation.getEngine((byte) 1);
    var prepareResult = engine1.timeoutForTest();
    prepareResult.ifPresent(msg -> simulation.deliverMessages(List.of(msg)));

    assertThat(engine1.isLeader()).isTrue();
  }

  @Test
  void shouldAcquireLockWithTwoNodes() {
    // we must define time when the leader gets the message not when the message is fixed as that will be different times on different servers
    final var expiryTime = LockStore.expiryTimeWithSafetyGap(Duration.ofSeconds(30), Duration.ofSeconds(1));
    // Given: Two-node simulation setup
    final var cmd = new LockServerCommandValue.TryAcquireLock("test-lock", expiryTime);

    CompletableFuture<LockServerReturnValue> future = new CompletableFuture<>();

    // When: Process command on leader node
    simulation.getServer((byte) 1).processCommand(cmd, future);

    // Then: Verify result and consistency across nodes
    LockServerReturnValue result = future.join();
    assertNotNull(result);
    assertThat(result).isInstanceOf(LockServerReturnValue.TryAcquireLockReturn.class);

    var lockResult = (LockServerReturnValue.TryAcquireLockReturn) result;
    assertThat(lockResult.result()).isPresent();
    assertThat(lockResult.result().get().lockId()).isEqualTo("test-lock");

    assertThat(simulation.getEngine((byte) 1).getProgress().highestFixedIndex()).isEqualTo(2);
    assertThat(simulation.getEngine((byte) 2).getProgress().highestFixedIndex()).isEqualTo(2);

    Optional<LockStore.LockEntry> node1OptionalLock = simulation.getStore((byte) 1).getLock("test-lock");
    assertThat(node1OptionalLock).isPresent();
    final var lock1 = node1OptionalLock.get();

    Optional<LockStore.LockEntry> node2OptionalLock = simulation.getStore((byte) 2).getLock("test-lock");
    assertThat(node2OptionalLock).isPresent();
    final var lock2 = node2OptionalLock.get();

    // The lock should be the same on both nodes
    assertThat(lock1).isEqualTo(lock2);

    // the lock should be the lock that we expect
    assertThat(lock1.lockId()).isEqualTo("test-lock");
    // and the stamp should be the slot index
    assertThat(lock1.stamp()).isEqualTo(2L);
  }
}
