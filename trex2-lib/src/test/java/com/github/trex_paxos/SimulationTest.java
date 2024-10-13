package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.Simulation.LOGGER;
import static org.assertj.core.api.Assertions.assertThat;

public class SimulationTest {

  @BeforeAll
  public static void init(){
    LoggerConfig.initialize();
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
    final var messages = simulation.run(45, false);

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

    LOGGER.info("lastCommits.size(): " + lastCommits.size());

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

  public void testClientWork(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // no code start rather we will make a leader
    makeLeader(simulation);

    // when we run for 15 iterations with client data
    simulation.run(15, true);

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);

    // and we should have the same commit logs
    assertThat(consistentJournals(
        simulation.trexEngine1.journal.fakeJournal,
        simulation.trexEngine2.journal.fakeJournal,
        simulation.trexEngine3.journal.fakeJournal

    )).isTrue();
  }

  @Test
  public void testClientWorkLossyNetwork() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(56734);

    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // first force a leader as we have separate tests for leader election. This is a partitioned network test.
    makeLeader(simulation);

    // TODO is run length the same as the number of iterations that send messages? Does that matter in practice for the nemesis?
    int runLength = 15;

    final Function<Simulation.Send, Stream<TrexMessage>> nemesis = makeNemesis(
        runLength,
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

    // assert that we ended with only one leader
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);

    LOGGER.info("sizes: " + simulation.trexEngine1.journal.fakeJournal.size() + " " + simulation.trexEngine2.journal.fakeJournal.size() + " " + simulation.trexEngine3.journal.fakeJournal.size());

    // and we should have the same commit logs
    assertThat(consistentJournals(
        simulation.trexEngine1.journal.fakeJournal,
        simulation.trexEngine2.journal.fakeJournal,
        simulation.trexEngine3.journal.fakeJournal

    )).isTrue();

  }

  private static Function<Simulation.Send, Stream<TrexMessage>> makeNemesis(
      int runLength,
      Simulation.TestablePaxosEngine engine1,
      Simulation.TestablePaxosEngine engine2,
      Simulation.TestablePaxosEngine engine3) {

    final var enginesAsList = List.of(engine1, engine2, engine3);

    final var counter = new java.util.concurrent.atomic.AtomicLong();

    return send -> {
      // which node to partition
      final var partitionedNodeIndex = counter.getAndIncrement() % 3;

      // Convert immutable list to mutable list
      final var mutableEnginesList = new java.util.ArrayList<>(enginesAsList);
      return switch (send.message()) {
        case BroadcastMessage m -> {
          // Remove the entry at partitionedNodeIndex
          mutableEnginesList.remove((int) partitionedNodeIndex);
          mutableEnginesList.forEach(e -> LOGGER.info("\t" + e.trexNode.nodeIdentifier() + " <- " + m));
          yield mutableEnginesList.stream().map(e -> e.paxos(m)).flatMap(p -> p.messages().stream());
        }
        case DirectMessage m -> {
          // Check if m.to() matches partitionedNodeIndex
          if (m.to() == partitionedNodeIndex) {
            LOGGER.info("\t" + m.to() + " <- null");
            yield Stream.empty();
          } else {
            LOGGER.info("\t" + m.to() + " <- " + m);
            // engines are 1 indexed but lists are zero indexed
            yield enginesAsList.get(m.to() - 1).paxos(m).messages().stream();
          }
        }
        case AbstractCommand abstractCommand ->
            throw new AssertionError("Unexpected command message: " + abstractCommand);
      };
    };
  }

  /**
   * This logic will iteration over the journals and ensure that they are not inconsistent.
   */
  boolean consistentJournals(NavigableMap<Long, Accept> fakeJournal1, NavigableMap<Long, Accept> fakeJournal2, NavigableMap<Long, Accept> fakeJournal3) {
    final NavigableMap<Long, Accept> longestJournal = fakeJournal1.size() > fakeJournal2.size() ? fakeJournal1 : fakeJournal2.size() > fakeJournal3.size() ? fakeJournal2 : fakeJournal3;
    return longestJournal.entrySet().stream().allMatch(e -> {
      final var logIndex = e.getKey();
      final var accept = e.getValue();
      LOGGER.info("logIndex: " + logIndex +
          "\n\taccept1: " + Optional.ofNullable(fakeJournal1.get(logIndex)).map(Objects::toString).orElse("null") +
          "\n\taccept2: " + Optional.ofNullable(fakeJournal2.get(logIndex)).map(Objects::toString).orElse("null") +
          "\n\taccept3: " + Optional.ofNullable(fakeJournal3.get(logIndex)).map(Objects::toString).orElse("null"));
      return Optional.ofNullable(fakeJournal2.get(logIndex)).map(a -> a.equals(accept)).orElse(true)
          && Optional.ofNullable(fakeJournal3.get(logIndex)).map(a -> a.equals(accept)).orElse(true);
    });
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

    LOGGER.info("Leader: " + leader.trexNode.nodeIdentifier());
  }

}
