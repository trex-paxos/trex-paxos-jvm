package com.github.trex_paxos;

import com.github.trex_paxos.msg.AbstractCommand;
import com.github.trex_paxos.msg.BroadcastMessage;
import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.TrexMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.Simulation.LOGGER;
import static com.github.trex_paxos.Simulation.inconsistentCommittedIndex;
import static org.assertj.core.api.Assertions.assertThat;

public class SimulationTest {

  static {
    LoggerConfig.initialize();
    //Logger.getLogger("").setLevel(Level.OFF);
  }

  // TODO this is an perfect network leader election test. We need to add a tests for a partitioned and clients on a code start.
  @Test
  public void testLeaderElection1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(1234);
    IntStream.range(0, 1000).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
      testLeaderElection(rng);
        }
    );
  }

  public void testLeaderElection(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

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
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);
  }

  @Test
  public void testClientWorkPerfectNetwork1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(9876);
    IntStream.range(0, 1000).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
          testClientWork(rng);
        }
    );
  }

  @Test
  public void testClientWorkPerfectNetwork() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(9876);
    testClientWork(rng);
  }

  @Test
  public void testClientWork() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(9876);
    testClientWork(rng);
  }

  public void testClientWork(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // no code start rather we will make a leader
    makeLeader(simulation);

    // when we run for 15 iterations with client data
    simulation.run(15, true);

    final var badCommandIndex = inconsistentCommittedIndex(
        simulation.trexEngine1.allCommandsMap,
        simulation.trexEngine2.allCommandsMap,
        simulation.trexEngine3.allCommandsMap
    );

    assertThat(badCommandIndex.isEmpty()).isTrue();

    assertThat(consistentCommits(
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3
    )).isTrue();

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);
  }

  @Test
  public void testClientWorkLossyNetwork1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(56734);

    IntStream.range(0, 1000).forEach(i -> {
          LOGGER.info("\n ================= \nstarting iteration: " + i);
          testWorkLossyNetwork(rng);
        }
    );
  }

  @Test
  public void testClientWorkLossyNetwork() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(56734);
    final var min = testWorkLossyNetwork(rng);
    assertThat(min).isGreaterThan(10);
  }

  private int testWorkLossyNetwork(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // first force a leader as we have separate tests for leader election. This is a partitioned network test.
    makeLeader(simulation);

    int runLength = 30;

    final var counter = new AtomicLong();

    final var nemesis = makeNemesis(
        _ -> (byte) (counter.getAndIncrement() % 3),
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3
    );

    // when we run for 15 iterations with client data
    simulation.run(runLength, true, nemesis);

    assertThat(inconsistentCommittedIndex(
        simulation.trexEngine1.allCommandsMap,
        simulation.trexEngine2.allCommandsMap,
        simulation.trexEngine3.allCommandsMap
    ).isEmpty()).isTrue();

    assertThat(consistentCommits(
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3
    )).isTrue();

    return Math.min(
        simulation.trexEngine1.journal.fakeJournal.size(),
        Math.min(
            simulation.trexEngine2.journal.fakeJournal.size(),
            simulation.trexEngine3.journal.fakeJournal.size()
        ));
  }

  @Test
  public void testWorkRotationNetworkPartition100() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(634546345);
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
    RandomGenerator rng = Simulation.repeatableRandomGenerator(5);
    testWorkRotationNetworkPartition(rng);
  }

  private int testWorkRotationNetworkPartition(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // first force a leader as we have separate tests for leader election. This is a partitioned network test.
    makeLeader(simulation);

    LOGGER.info("START ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ START");

    int runLength = 60;

    final var nemesis = getRotatingPartitionNemesis(simulation, runLength / 3);

    // run with client data
    simulation.run(runLength, true, nemesis);

    LOGGER.info("\n\nEMD ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ END\n\n");
    LOGGER.info(simulation.trexEngine1.role() + " " + simulation.trexEngine2.role() + " " + simulation.trexEngine3.role());
    LOGGER.info("command sizes: " + simulation.trexEngine1.allCommands().size() + " "
        + simulation.trexEngine2.allCommands().size() + " "
        + simulation.trexEngine3.allCommands().size());
    LOGGER.info("journal sizes: " + simulation.trexEngine1.journal.fakeJournal.size() +
        " " + simulation.trexEngine2.journal.fakeJournal.size() +
        " " + simulation.trexEngine3.journal.fakeJournal.size());

    assertThat(consistentCommits(
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3
    )).isTrue();

    return Math.min(
        simulation.trexEngine1.allCommands().size(), Math.min(
            simulation.trexEngine2.allCommands().size(),
            simulation.trexEngine3.allCommands().size()
        )
    );
  }

  private boolean consistentCommits(
      Simulation.TestablePaxosEngine engine1,
      Simulation.TestablePaxosEngine engine2,
      Simulation.TestablePaxosEngine engine3) {
    final var maxLength =
        Math.max(engine1.allCommands().size(), Math.max(
            engine2.allCommands().size(), engine3.allCommands().size()));
    return IntStream.range(0, maxLength).allMatch(index -> {
      final Optional<AbstractCommand> optional1 = engine1.allCommands().stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional2 = engine2.allCommands().stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional3 = engine3.allCommands().stream().skip(index).findFirst();
      // Check if all non-empty values are equal
      //noinspection UnnecessaryLocalVariable
      final var result =
          optional1.map(
                  // if one is defined check it against the two and three
                  a1 -> optional2.map(a1::equals).orElse(true) && optional3.map(a1::equals).orElse(true)
              )
              // if one is not defined then check two against three
              .orElse(true)
              &&
              optional2.map(
                  // check two against three
                  a2 -> optional3.map(a2::equals).orElse(true)
              ).orElse(true); // if one and two are not defined it does not matter what three is
      return result;
    });
  }

  private static BiFunction<Simulation.Send, Long, Stream<TrexMessage>> getRotatingPartitionNemesis(Simulation simulation, int period) {
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
          isolatedNode.set(counter.getAndIncrement() / period % 3);
          if (isolatedNode.get() != lastIsolatedNode.get()) {
            LOGGER.info("NEW isolatedNode: " + (isolatedNode.get() + 1));
          }
          return (byte) (isolatedNode.get());
        },
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3
    );
  }

  private static BiFunction<Simulation.Send, Long, Stream<TrexMessage>> makeNemesis(
      Function<Long, Byte> timeToPartitionedNode,
      Simulation.TestablePaxosEngine engine1,
      Simulation.TestablePaxosEngine engine2,
      Simulation.TestablePaxosEngine engine3) {

    final var enginesAsList = List.of(engine1, engine2, engine3);

    return (send, time) -> {
      // which node to partition
      final var partitionedNodeIndex = timeToPartitionedNode.apply(time);

      final var reachableNodes = new ArrayList<>(enginesAsList);
      reachableNodes.remove((int) partitionedNodeIndex);

      return send.messages().stream().flatMap(x -> switch (x) {
        case BroadcastMessage m -> {
          if (m.from() == partitionedNodeIndex + 1) {
            yield Stream.empty();
          }
          yield reachableNodes.stream()
              .flatMap(engine -> engine.paxos(m).messages().stream());
        }
        case DirectMessage m -> {
          if (m.to() == partitionedNodeIndex + 1 || m.from() == partitionedNodeIndex + 1) {
            yield Stream.empty();
          }
          yield enginesAsList.get(m.to() - 1).paxos(m).messages().stream();
        }
      });
    };
  }

  void makeLeader(Simulation simulation) {

    final var leader = simulation.trexEngine1;

    simulation.trexEngine1.start();
    simulation.trexEngine2.start();
    simulation.trexEngine3.start();

    // when we call timeout it will make a new prepare and self-promise to become Recoverer
    leader.timeout().ifPresent(p -> {
      // in a three node cluster we need only one other node to be reachable to become leader
      final var r = simulation.trexEngine2.paxos(p);
      final var lm = leader.paxos(r.messages().getFirst());
      // we need to send accept messages to the other nodes
      final var r1 = simulation.trexEngine2.paxos(lm.messages().getFirst());
      simulation.trexEngine3.paxos(lm.messages().getFirst());
      // we only need one accept response to get a commit
      final var r3 = leader.paxos(r1.messages().getFirst());
      simulation.trexEngine2.paxos(r3.messages().getFirst());
      simulation.trexEngine3.paxos(r3.messages().getFirst());
    });

    LOGGER.info(leader.trexNode.nodeIdentifier + " == LEADER");
  }
}
