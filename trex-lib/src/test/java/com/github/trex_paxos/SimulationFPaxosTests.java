/*
 * Copyright 2024 Simon Massey
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
package com.github.trex_paxos;

import com.github.trex_paxos.msg.BroadcastMessage;
import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.TrexMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.SimulationFPaxos.inconsistentFixedIndex2;
import static com.github.trex_paxos.TrexLogger.LOGGER;
import static org.assertj.core.api.Assertions.assertThat;

public class SimulationFPaxosTests {

  @BeforeAll
  static void setupLogging() {

    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
    final Level level = Level.parse(logLevel);

    LOGGER.setLevel(level);
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(level);
    LOGGER.addHandler(consoleHandler);

    // Configure SessionKeyManager logger
    Logger sessionKeyManagerLogger = Logger.getLogger("");
    sessionKeyManagerLogger.setLevel(level);
    ConsoleHandler skmHandler = new ConsoleHandler();
    skmHandler.setLevel(level);
    sessionKeyManagerLogger.addHandler(skmHandler);

    // Optionally disable parent handlers if needed
    LOGGER.setUseParentHandlers(false);
    sessionKeyManagerLogger.setUseParentHandlers(false);
  }

  @Test
  public void testLeaderElection1000() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(1234);
    IntStream.range(0, 1000).forEach(i -> {
          LOGGER.info("\n ================= \nstarting iteration: " + i);
          testLeaderElection(rng);
        }
    );
  }

  @Test
  public void testLeaderElection() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(1234);
    testLeaderElection(rng);
  }

  public void testLeaderElection(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new SimulationFPaxos(rng, 30);

    // we do a cold cluster start with no prior leader in the journals
    simulation.coldStart();

    // when we run for a maximum of 50 iterations
    simulation.run(50, false);

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexNode.TrexRole.FOLLOW, TrexNode.TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexNode.TrexRole.LEAD).count()).isEqualTo(1);
  }

  @Test
  public void testClientWorkPerfectNetwork1000() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(9876);
    IntStream.range(0, 1).forEach(i -> {
          LOGGER.info("\n ================= \nstarting iteration: " + i);
          testClientWork(rng);
        }
    );
  }

  @Test
  public void testClientWorkPerfectNetwork() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(9876);
    testClientWork(rng);
  }

  @Test
  public void testClientWork() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(1234);
    testClientWork(rng);
  }

  public void testClientWork(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new SimulationFPaxos(rng, 30);

    // no code start rather we will make a leader
    makeLeader(simulation);

    // when we run for 30 iterations with client data
    simulation.run(30, true);

    final var badCommandIndex = inconsistentFixedIndex2(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine4.nodeIdentifier())
    );

    assertThat(badCommandIndex.isEmpty()).isTrue();

    assertThat(consistentFixed(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine4.nodeIdentifier())
    )).isTrue();

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    // assert that we ended without multiple leaders
    assertThat(roles.stream().filter(r -> r == TrexNode.TrexRole.LEAD).count()).isEqualTo(1);
  }

  @Test
  public void testClientWorkLossyNetwork1000() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(56734);

    final var maxOfMinimum = new AtomicInteger(0);

    IntStream.range(0, 1000).forEach(i -> {
          LOGGER.info("\n ================= \nstarting iteration: " + i);
          final var minLogLength = testWorkLossyNetwork(rng);
          if (minLogLength > maxOfMinimum.get()) {
            maxOfMinimum.set(minLogLength);
          }
        }
    );

    assertThat(maxOfMinimum.get()).isGreaterThan(4);
  }

  @Test
  public void testClientWorkLossyNetwork() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(4566);
    var min = 0;
    var counter = 0;
    while (min == 0 && counter < 5) {
      min = testWorkLossyNetwork(rng);
      counter++;
    }
    assertThat(min).isGreaterThan(0);
  }

  /// This returns the minimum command size of the three engines
  private int testWorkLossyNetwork(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new SimulationFPaxos(rng, 30);

    // first force a leader as we have separate tests for leader election. This is a partitioned network test.
    makeLeader(simulation);

    int runLength = 35;

    final var counter = new AtomicLong();

    final var nemesis = makeNemesis(
        _ -> (byte) (counter.getAndIncrement() % 5),
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3,
        simulation.trexEngine4
    );

    // when we run for 15 iterations with client data
    simulation.run(runLength, true, nemesis);

    assertThat(
        inconsistentFixedIndex2(
            simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
            simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
            simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
            simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier())
        ).isEmpty()).isTrue();

    assertThat(consistentFixed(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine4.nodeIdentifier())
    )).isTrue();

    return Math.min(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()).size(),
        Math.min(
            simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()).size(),
            simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()).size()
        ));
  }

  @Test
  public void testWorkRotationNetworkPartition100() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(634546345);
    final var max = new AtomicInteger();
    IntStream.range(0, 100).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
      final var min = testWorkRotationNetworkPartition(rng);
      if (min > max.get()) {
        max.set(min);
      }
    });
    assertThat(max.get()).isGreaterThan(30);
  }

  @Test
  public void testWorkRotationNetworkPartition() {
    RandomGenerator rng = SimulationFPaxos.repeatableRandomGenerator(5);
    testWorkRotationNetworkPartition(rng);
  }

  private int testWorkRotationNetworkPartition(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new SimulationFPaxos(rng, 30);

    // first force a leader as we have separate tests for leader election. This is a partitioned network test.
    makeLeader(simulation);

    LOGGER.info("START ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ START");

    int runLength = 60;

    final var nemesis = getRotatingPartitionNemesis(simulation, runLength / 3);

    // run with client data
    simulation.run(runLength, true, nemesis);

    LOGGER.info("\n\nEMD ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ END\n\n");
    LOGGER.info(simulation.trexEngine1.role() + " " + simulation.trexEngine2.role() + " " + simulation.trexEngine3.role());

    final var c1 = simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier());
    final var c2 = simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier());
    final var c3 = simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier());

    LOGGER.info("command sizes: " + c1.size() + " "
        + c2.size() + " "
        + c3.size());

    LOGGER.info("journal sizes: " + simulation.trexEngine1.journal.fakeJournal.size() +
        " " + simulation.trexEngine2.journal.fakeJournal.size() +
        " " + simulation.trexEngine3.journal.fakeJournal.size());

    assertThat(consistentFixed(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()),
        simulation.allCommandsMap.get(simulation.trexEngine4.nodeIdentifier())
    )).isTrue();

    return Math.min(
        simulation.allCommandsMap.get(simulation.trexEngine1.nodeIdentifier()).size(),
        Math.min(
            simulation.allCommandsMap.get(simulation.trexEngine2.nodeIdentifier()).size(),
            simulation.allCommandsMap.get(simulation.trexEngine3.nodeIdentifier()).size()
        )
    );
  }

  private boolean consistentFixed(
      TreeMap<Long, Command> engine1,
      TreeMap<Long, Command> engine2,
      TreeMap<Long, Command> engine3,
      TreeMap<Long, Command> engine4
  ) {
    final var maxLength =
        Math.max(
            engine1.size(),
            Math.max(
                engine2.size(),
                Math.max(engine3.size(), engine4.size())
            )
        );
    return IntStream.range(0, maxLength).allMatch(index -> {
      final Optional<Command> optional1 = engine1.values().stream().skip(index).findFirst();
      final Optional<Command> optional2 = engine2.values().stream().skip(index).findFirst();
      final Optional<Command> optional3 = engine3.values().stream().skip(index).findFirst();
      final Optional<Command> optional4 = engine4.values().stream().skip(index).findFirst();
      // Check if all non-empty values are equal
      //noinspection UnnecessaryLocalVariable
      final var result =
          optional1.map(
                  // if one is defined check it against the two and three
                  a1 -> optional2.map(a1::equals).orElse(true)
                      && optional3.map(a1::equals).orElse(true) && optional4.map(a1::equals).orElse(true)
              )
              // if one is not defined then check two against three
              .orElse(true)
              &&
              optional2.map(
                  // check two against three
                  a2 -> optional3.map(a2::equals).orElse(true) && optional4.map(a2::equals).orElse(true)
              ).orElse(true)
              && optional3.map(
              a3 -> optional4.map(a3::equals).orElse(true)
          ).orElse(true); // if one and two are not defined it does not matter what three is
      return result;
    });
  }

  private static BiFunction<SimulationFPaxos.Send, Long, Stream<TrexMessage>> getRotatingPartitionNemesis(SimulationFPaxos simulation, int period) {
    final var counter = new AtomicLong();
    final var latestTime = new AtomicLong();
    final var isolatedNode = new AtomicLong();
    final var lastIsolatedNode = new AtomicLong();
    return makeNemesis(
        time -> {
          final var lastTime = latestTime.get();
          if (time > lastTime) {
            counter.getAndIncrement();
            latestTime.set(time);
          }
          lastIsolatedNode.set(isolatedNode.get());
          isolatedNode.set(counter.getAndIncrement() / period % 4);
          if (isolatedNode.get() != lastIsolatedNode.get()) {
            LOGGER.info("NEW isolatedNode: " + (isolatedNode.get() + 1));
          }
          return (byte) (isolatedNode.get());
        },
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3,
        simulation.trexEngine4
    );
  }

  static <R> BiFunction<SimulationFPaxos.Send, Long, Stream<TrexMessage>> makeNemesis(
      Function<Long, Byte> timeToPartitionedNode,
      TestablePaxosEngine<R> engine1,
      TestablePaxosEngine<R> engine2,
      TestablePaxosEngine<R> engine3,
      TestablePaxosEngine<R> engine4
  ) {

    final var enginesAsList = List.of(engine1, engine2, engine3, engine4);

    return (send, time) -> {

      final var reachableNodes = new ArrayList<>(enginesAsList);

      // which node to partition
      final var partitionedNodeIndex = timeToPartitionedNode.apply(time);

      if (partitionedNodeIndex > 0 && partitionedNodeIndex < enginesAsList.size()) {
        LOGGER.info("Partitioning node: " + partitionedNodeIndex);
        reachableNodes.remove((int) partitionedNodeIndex);
      }

      return send.messages().stream().flatMap(x -> switch (x) {
        case BroadcastMessage m -> {
          if (m.from() == partitionedNodeIndex + 1) {
            yield Stream.empty();
          }
          yield reachableNodes.stream()
              .flatMap(engine -> engine.paxos(List.of(m)).messages().stream());
        }
        case DirectMessage m -> {
          if (m.to() == partitionedNodeIndex + 1 || m.from() == partitionedNodeIndex + 1) {
            yield Stream.empty();
          }
          yield enginesAsList.get(m.to() - 1).paxos(List.of(m)).messages().stream();
        }
      });
    };
  }

  void makeLeader(SimulationFPaxos simulation) {
      final var leader = simulation.trexEngine1;

      simulation.timeout(leader.trexNode()).ifPresent(p -> {
          // Need 3 responses for prepare quorum (including self)
          final var r2 = simulation.trexEngine2.paxos(List.of(p));
          final var r3 = simulation.trexEngine3.paxos(List.of(p));

          // Collect 2 follower responses + self to reach prepare quorum of 3
          final var lm = leader.paxos(List.of(
              r2.messages().getFirst(),
              r3.messages().getFirst()
          ));

          // Send accepts to all 4 nodes (quorum size 2)
          simulation.trexEngine2.paxos(List.of(lm.messages().getFirst()));
          simulation.trexEngine3.paxos(List.of(lm.messages().getFirst()));
          simulation.trexEngine4.paxos(List.of(lm.messages().getFirst()));

          // Collect 1 accept response to finalize (quorum size 2)
          final var r2a = simulation.trexEngine2.paxos(List.of(lm.messages().getFirst()));
          final var r3a = simulation.trexEngine3.paxos(List.of(lm.messages().getFirst()));

          // Finalize leadership
          leader.paxos(List.of(r2a.messages().getFirst()));
          leader.paxos(List.of(r3a.messages().getFirst()));
      });

      LOGGER.info(leader.trexNode.nodeIdentifier + " == LEADER");
  }
}
