package com.github.trex_paxos;

import com.github.trex_paxos.msg.BroadcastMessage;
import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.TrexMessage;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.Simulation.LOGGER;
import static org.assertj.core.api.Assertions.assertThat;

public class SimulationEvenGambitTests {

  @Test
  public void testLeaderElectionEvenGambit1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(1234);
    IntStream.range(0, 1000).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
      testLeaderElectionEvenGambit(rng);
    });
  }

  public void testLeaderElectionEvenGambit(RandomGenerator rng) {
    // given a repeatable test setup with 4 nodes
    final var simulation = new Simulation(rng, 30, true);

    // cold cluster start with no prior leader
    simulation.coldStart();

    // run for maximum of 50 iterations
    simulation.run(50, false);

    // verify single leader and rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    assertThat(roles).containsOnly(TrexNode.TrexRole.FOLLOW, TrexNode.TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexNode.TrexRole.LEAD).count()).isEqualTo(1);
  }

  @Test
  public void testClientWorkEvenGambit() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(9876);
    final var simulation = new Simulation(rng, 30, true);

    makeLeader(simulation);
    simulation.run(15, true);

    final var badCommandIndex = inconsistentFixedIndex(
        simulation.trexEngine1.allCommandsMap,
        simulation.trexEngine2.allCommandsMap,
        simulation.trexEngine3.allCommandsMap,
        simulation.trexEngine4.allCommandsMap
    );

    assertThat(badCommandIndex.isEmpty()).isTrue();
    assertThat(consistentFixed(
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3,
        simulation.trexEngine4
    )).isTrue();

    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    assertThat(roles).containsOnly(TrexNode.TrexRole.FOLLOW, TrexNode.TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexNode.TrexRole.LEAD).count()).isEqualTo(1);
  }

  private boolean consistentFixed(
      TestablePaxosEngine engine1,
      TestablePaxosEngine engine2,
      TestablePaxosEngine engine3,
      TestablePaxosEngine engine4) {
    final var maxLength =
        Math.max(engine1.allCommands().size(), Math.max(
            engine2.allCommands().size(), Math.max(engine3.allCommands().size(), engine4.allCommands().size())));
    return IntStream.range(0, maxLength).allMatch(index -> {
      final Optional<AbstractCommand> optional1 = engine1.allCommands().stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional2 = engine2.allCommands().stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional3 = engine3.allCommands().stream().skip(index).findFirst();
      final Optional<AbstractCommand> optional4 = engine4.allCommands().stream().skip(index).findFirst();
      // Check if all non-empty values are equal
      // if one is defined check it against the two, three, and four
      // if one is not defined then check two against three and four
      // check two against three and four
      // check three against four
      // if one, two, and three are not defined it does not matter what four is
      return optional1.map(
              // if one is defined check it against the two, three, and four
              a1 -> optional2.map(a1::equals).orElse(true) && optional3.map(a1::equals).orElse(true) && optional4.map(a1::equals).orElse(true)
          )
          // if one is not defined then check two against three and four
          .orElse(true)
          &&
          optional2.map(
              // check two against three and four
              a2 -> optional3.map(a2::equals).orElse(true) && optional4.map(a2::equals).orElse(true)
          ).orElse(true)
          &&
          optional3.map(
              // check three against four
              a3 -> optional4.map(a3::equals).orElse(true)
          ).orElse(true);
    });
  }


  static OptionalLong inconsistentFixedIndex(TreeMap<Long, AbstractCommand> c1,
                                             TreeMap<Long, AbstractCommand> c2,
                                             TreeMap<Long, AbstractCommand> c3,
                                             TreeMap<Long, AbstractCommand> c4) {
    final var c1last = !c1.isEmpty() ? c1.lastKey() : 0;
    final var c2last = !c2.isEmpty() ? c2.lastKey() : 0;
    final var c3last = !c3.isEmpty() ? c3.lastKey() : 0;
    final var c4last = !c4.isEmpty() ? c4.lastKey() : 0;
    final var maxLength = Math.max(
        c1last, Math.max(
            c2last,
            Math.max(c3last, c4last)));
    return LongStream.range(0, maxLength).filter(i -> {
      final Optional<AbstractCommand> optC1 = Optional.ofNullable(c1.get(i));
      final Optional<AbstractCommand> optC2 = Optional.ofNullable(c2.get(i));
      final Optional<AbstractCommand> optC3 = Optional.ofNullable(c3.get(i));
      final Optional<AbstractCommand> optC4 = Optional.ofNullable(c4.get(i));

      // Check if all non-empty values are equal
      final var result =
          optC1.map(
                  // if one is defined check it against the two, three, and four
                  a1 -> optC2.map(a1::equals).orElse(true) && optC3.map(a1::equals).orElse(true) && optC4.map(a1::equals).orElse(true)
              )
              // if one is not defined then check two against three and four
              .orElse(true)
              &&
              optC2.map(
                  // check two against three and four
                  a2 -> optC3.map(a2::equals).orElse(true) && optC4.map(a2::equals).orElse(true)
              ).orElse(true)
              &&
              optC3.map(
                  // check three against four
                  a3 -> optC4.map(a3::equals).orElse(true)
              ).orElse(true); // if one, two, and three are not defined it does not matter what four is
      if (!result) {
        LOGGER.info("command mismatch logIndex=" + i + ":\n\t" + optC1 + "\n\t" + optC2 + "\n\t" + optC3 + "\n\t" + optC4);
      }
      return !result;
    }).findFirst();
  }


  @Test
  public void testClientWorkLossyNetworkEvenGambit1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(56734);
    final var maxOfMin = new AtomicInteger(0);

    IntStream.range(0, 1000).forEach(i -> {
      LOGGER.info("\n ================= \nstarting iteration: " + i);
      final var minLogLength = testWorkLossyNetworkEvenGambit(rng);
      if (minLogLength > maxOfMin.get()) {
        maxOfMin.set(minLogLength);
      }
    });
    assertThat(maxOfMin.get()).isGreaterThan(10);
  }

  private int testWorkLossyNetworkEvenGambit(RandomGenerator rng) {
    final var simulation = new Simulation(rng, 30, true);

    makeLeader(simulation);
    int runLength = 30;
    final var counter = new AtomicLong();

    final var nemesis = makeNemesis(
        _ -> (byte) (counter.getAndIncrement() % 5),
        simulation.trexEngine1,
        simulation.trexEngine2,
        simulation.trexEngine3,
        simulation.trexEngine4
    );

    simulation.run(runLength, true, nemesis);

    assertThat(inconsistentFixedIndex(
        simulation.trexEngine1.allCommandsMap,
        simulation.trexEngine2.allCommandsMap,
        simulation.trexEngine3.allCommandsMap,
        simulation.trexEngine4.allCommandsMap
    ).isEmpty()).isTrue();

    return Math.min(
        Math.min(simulation.trexEngine1.allCommandsMap.size(),
            simulation.trexEngine2.allCommandsMap.size()),
        Math.min(simulation.trexEngine3.allCommandsMap.size(),
            simulation.trexEngine4.allCommandsMap.size())
    );
  }

  void makeLeader(Simulation simulation) {

    final var leader = simulation.trexEngine1;

    simulation.trexEngine1.start();
    simulation.trexEngine2.start();
    simulation.trexEngine3.start();
    simulation.trexEngine4.start();

    // when we call timeout it will make a new prepare and self-promise to become Recoverer
    leader.timeout().ifPresent(p -> {
      // in a three node cluster we need only one other node to be reachable to become leader
      // in a four node cluster we need the primary and one other node to be become ladder due to even mode gambit
      final var r = simulation.trexEngine2.paxos(p);
      final var lm = leader.paxos(r.messages().getFirst());
      // we need to send accept messages to the other nodes
      final var r1 = simulation.trexEngine2.paxos(lm.messages().getFirst());
      simulation.trexEngine3.paxos(lm.messages().getFirst());
      // we only need one accept response to get a fixed value
      final var r3 = leader.paxos(r1.messages().getFirst());
      simulation.trexEngine2.paxos(r3.messages().getFirst());
      simulation.trexEngine3.paxos(r3.messages().getFirst());
    });

    LOGGER.info(leader.trexNode.nodeIdentifier + " == LEADER");
  }

  private static BiFunction<Simulation.Send, Long, Stream<TrexMessage>> makeNemesis(
      Function<Long, Byte> timeToPartitionedNode,
      TestablePaxosEngine engine1,
      TestablePaxosEngine engine2,
      TestablePaxosEngine engine3,
      TestablePaxosEngine engine4) {

    final var enginesAsList = List.of(engine1, engine2, engine3, engine4);

    return (send, time) -> {

      final var reachableNodes = new ArrayList<>(enginesAsList);

      // which node to partition
      final var partitionedNodeIndex = timeToPartitionedNode.apply(time);

      if (partitionedNodeIndex >= 0 && partitionedNodeIndex < enginesAsList.size()) {
        LOGGER.info("Partitioning node: " + partitionedNodeIndex);
        reachableNodes.remove((int) partitionedNodeIndex);
      }

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

}
