package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.Simulation.LOGGER;
import static com.github.trex_paxos.Simulation.matchingCommands;
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

    // when we run for a maximum of 45 iterations
    final var messages = simulation.run(50, false);

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);

    // we are heartbeating at half the rate of the time. so if we have no other leader or recoverer in the last three
    // commits it we would be a stable leader
    final var lastCommits = messages.reversed()
        .stream()
        .takeWhile(m -> m instanceof Commit)
        .toList();

    assertThat(lastCommits).hasSizeGreaterThan(2);
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

    assertThat(matchingCommands(
        simulation.trexEngine1.allCommands,
        simulation.trexEngine2.allCommands,
        simulation.trexEngine3.allCommands
    )).isTrue();

    // and we should have the same commit logs
    assertThat(consistentJournals(
        simulation.trexEngine1.journal,
        simulation.trexEngine2.journal,
        simulation.trexEngine3.journal
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
    testWorkLossyNetwork(rng);
  }

  private void testWorkLossyNetwork(RandomGenerator rng) {
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

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    assertThat(matchingCommands(
        simulation.trexEngine1.allCommands,
        simulation.trexEngine2.allCommands,
        simulation.trexEngine3.allCommands
    )).isTrue();

    // and we should have the same commit logs
    assertThat(consistentJournals(
        simulation.trexEngine1.journal,
        simulation.trexEngine2.journal,
        simulation.trexEngine3.journal
    )).isTrue();

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isGreaterThan(1);

    LOGGER.info("sizes: " + simulation.trexEngine1.journal.fakeJournal.size() + " " + simulation.trexEngine2.journal.fakeJournal.size() + " " + simulation.trexEngine3.journal.fakeJournal.size());
  }

  @Test
  public void testWorkRotationNetworkPartition100() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(634546345);
    IntStream.range(0, 100).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
      System.out.println("\niteration: " + i);
      testWorkRotationNetworkPartition(rng);
    });
  }

  private void testWorkRotationNetworkPartition(RandomGenerator rng) {
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
    LOGGER.info("sizes: " + simulation.trexEngine1.journal.fakeJournal.size() + " " + simulation.trexEngine2.journal.fakeJournal.size() + " " + simulation.trexEngine3.journal.fakeJournal.size());

    assertThat(matchingCommands(
        simulation.trexEngine1.allCommands,
        simulation.trexEngine2.allCommands,
        simulation.trexEngine3.allCommands
    )).isTrue();

    // and we should have the same commit logs
    assertThat(consistentJournals(
        simulation.trexEngine1.journal,
        simulation.trexEngine2.journal,
        simulation.trexEngine3.journal
    )).isTrue();

    assertThat(consistentCommits(
        simulation.trexEngine1.allCommands,
        simulation.trexEngine2.allCommands,
        simulation.trexEngine3.allCommands
    )).isTrue();

    final var minCommandLength = Math.min(
        simulation.trexEngine1.allCommands.size(), Math.min(
            simulation.trexEngine2.allCommands.size(),
            simulation.trexEngine3.allCommands.size()
        )
    );
    assertThat(minCommandLength).isGreaterThan(10);
  }

  private boolean consistentCommits(
      List<AbstractCommand> engine1,
      List<AbstractCommand> engine2,
      List<AbstractCommand> engine3) {
    final var maxLength =
        Math.max(engine1.size(), Math.max(
            engine2.size(), engine3.size()));
    return IntStream.range(0, maxLength).allMatch(index -> {
      final Optional<AbstractCommand> optional1 = engine1.stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional2 = engine2.stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional3 = engine3.stream().skip(index).findFirst();
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

  private boolean consistentJournals(
      Simulation.TransparentJournal journal1,
      Simulation.TransparentJournal journal2,
      Simulation.TransparentJournal journal3) {
    final var maxLength = Math.max(
        journal1.fakeJournal.size(), Math.max(
            journal2.fakeJournal.size(),
            journal3.fakeJournal.size()));
    return LongStream.range(0, maxLength).allMatch(e -> {
      final Optional<Accept> accept1 = journal1.getCommitted(e);
      final Optional<Accept> accept2 = journal2.getCommitted(e);
      final Optional<Accept> accept3 = journal3.getCommitted(e);

      // Check if all non-empty values are equal
      //noinspection UnnecessaryLocalVariable
      final var result =
          accept1.map(
                  // if one is defined check it against the two and three
                  a1 -> accept2.map(a1::equals).orElse(true) && accept3.map(a1::equals).orElse(true)
              )
              // if one is not defined then check two against three
              .orElse(true)
              &&
              accept2.map(
                  // check two against three
                  a2 -> accept3.map(a2::equals).orElse(true)
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
          yield enginesAsList.get(m.to()).paxos(m).messages().stream();
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
