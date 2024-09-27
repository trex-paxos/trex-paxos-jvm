package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class Simulation {
  static final Logger LOGGER = Logger.getLogger(Simulation.class.getName());
  private final RandomGenerator rng;
  private final long maxTimeout;
  private final long halfTimeout;

  public Simulation(RandomGenerator rng, long maxTimeout) {
    this.maxTimeout = maxTimeout;
    this.rng = rng;
    halfTimeout = maxTimeout / 2;
    assert this.maxTimeout > 1;
    assert this.halfTimeout > 0 && this.halfTimeout < this.maxTimeout;
  }

  static RandomGenerator repeatableRandomGenerator(long seed) {
    RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of("L64X128MixRandom");
    // Create a seeded random generator using the factory
    RandomGenerator rng = factory.create(seed);
    LOGGER.info("Simulation Using Seed: " + seed);
    return rng;
  }

  Map<Byte,Long> nodeTimeouts = new HashMap<>();

  sealed interface Event permits Heartbeat, Send, Timeout {
  }

  record Timeout(byte nodeIdentifier) implements Event {
  }

  record Send(byte from, TrexMessage message) implements Event {
  }

  record Heartbeat(byte nodeIdentifier) implements Event {
  }

  NavigableMap<Long,List<Event>> eventQueue = new TreeMap<>();

  private void resetTimeout(byte nodeIdentifier) {
    Optional.ofNullable(nodeTimeouts.get(nodeIdentifier)).ifPresent(timeout -> Optional.ofNullable(eventQueue.get(timeout)).ifPresent(events -> {
      events.remove(new Timeout(nodeIdentifier));
      if (events.isEmpty()) {
        eventQueue.remove(timeout);
      }
    }));
  }

  long now = 0;

  private long now() {
    return now;
  }

  private void tick(long now) {
    this.now = now;
    LOGGER.info("\ttick: " + now);
  }

  private void setRandomTimeout(byte nodeIdentifier) {
    final var timeout = rng.nextInt((int) maxTimeout);
    final var when = now() + timeout;
    nodeTimeouts.put(nodeIdentifier, when);
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    events.add(new Timeout(nodeIdentifier));
  }

  private void setHeartbeat(byte nodeIdentifier) {
    final var when = now() + halfTimeout;
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    events.add(new Heartbeat(nodeIdentifier));
  }

  final TrexEngine trexEngine1 = trexEngine((byte) 1);
  final TrexEngine trexEngine2 = trexEngine((byte) 2);
  final TrexEngine trexEngine3 = trexEngine((byte) 3);

  final Map<Byte, TrexEngine> engines = Map.of(
      (byte) 1, trexEngine1,
      (byte) 2, trexEngine2,
      (byte) 3, trexEngine3
  );

  void run(int iterations) {
    // start will launch some timeouts into the event queue
    trexEngine1.start();
    trexEngine2.start();
    trexEngine3.start();

    final var _ = IntStream.range(0, iterations).anyMatch(i -> {
      Optional.ofNullable(eventQueue.pollFirstEntry()).ifPresent(timeWithEvents -> {

        // advance the clock
        tick(timeWithEvents.getKey());

        // grab the events at this time
        final var events = timeWithEvents.getValue();

        // for what is in the queue of events at this time
        final List<TrexMessage> newMessages = events.stream().flatMap(event -> {
          LOGGER.info("\tevent: " + event);
          switch (event) {
            case Timeout timeout -> {
              // if it is a timeout collect the prepare message if the node is still a follower at this time
              final var prepare = switch (timeout.nodeIdentifier) {
                case 1 -> trexEngine1.timeout();
                case 2 -> trexEngine2.timeout();
                case 3 -> trexEngine3.timeout();
                default ->
                    throw new IllegalStateException("Unexpected node identifier for timeout: " + timeout.nodeIdentifier);
              };
              return prepare.stream();
            }

            // if it is a message that has arrived run paxos
            case Send send -> {
              return switch (send.message()) {
                case BroadcastMessage m ->
                    engines.values().stream().flatMap(engine -> engine.paxos(m).messages().stream());
                case DirectMessage m -> engines.get(m.to()).paxos(m).messages().stream();
              };
            }
            case Heartbeat heartbeat -> {
              // if it is a timeout collect the prepare message if the node is still a follower at this time
              final var commit = switch (heartbeat.nodeIdentifier) {
                case 1 -> trexEngine1.hearbeat();
                case 2 -> trexEngine2.hearbeat();
                case 3 -> trexEngine3.hearbeat();
                default ->
                    throw new IllegalStateException("Unexpected node identifier for heartbeat: " + heartbeat.nodeIdentifier);
              };
              return commit.stream();
            }
          }
        }).toList();

        if( !newMessages.isEmpty() ){
          LOGGER.info("\t\tnewMessages: " + newMessages.stream()
              .map(Object::toString)
              .collect(Collectors.joining(",")));

          // messages sent in the cluster will arrive after 1 time unit
          final var nextTime = now() + 1;
          // we add the messages to the event queue at that time
          final var nextTimeList = this.eventQueue.computeIfAbsent(nextTime, _ -> new ArrayList<>());
          nextTimeList.addAll(newMessages.stream().map(m -> new Send(m.from(), m)).toList());
        }
      });
      // if the event queue is empty we are done
      final var finished = this.eventQueue.isEmpty();
      if (finished) {
        LOGGER.info("finished on iteration: " + i);
      }
      return finished;
    });
  }

  private TestablePaxosEngine trexEngine(byte nodeIdentifier) {
    return new TestablePaxosEngine(nodeIdentifier,
        QuorumStrategy.SIMPLE_MAJORITY,
        new TransparentJournal(nodeIdentifier));
  }

  class TestablePaxosEngine extends TrexEngine {

    public TestablePaxosEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal) {
      super(new TrexNode(nodeIdentifier, quorumStrategy, journal));
    }

    @Override
    void setRandomTimeout() {
      Simulation.this.setRandomTimeout(trexNode.nodeIdentifier());
    }

    @Override
    void resetTimeout() {
      Simulation.this.resetTimeout(trexNode.nodeIdentifier);
    }

    @Override
    void setHeatbeat() {
      Simulation.this.setHeartbeat(trexNode.nodeIdentifier);
    }
  }

  static class TransparentJournal implements Journal {
    public TransparentJournal(byte nodeIdentifier) {
      progress = new Progress(nodeIdentifier);
    }

    Progress progress;

    @Override
    public void saveProgress(Progress progress) {
      this.progress = progress;
    }

    NavigableMap<Long, Accept> fakeJournal = new TreeMap<>();

    @Override
    public void journalAccept(Accept accept) {
      fakeJournal.put(accept.logIndex(), accept);
    }

    @Override
    public Progress loadProgress(byte nodeIdentifier) {
      return progress;
    }

    @Override
    public Optional<Accept> loadAccept(long logIndex) {
      return Optional.ofNullable(fakeJournal.get(logIndex));
    }
  }
}

public class SimulationTest {

  @BeforeAll
  public static void init(){
    LoggerConfig.initialize();
  }

  @Test
  public void testSimulations() {
    // given a repeatable test setup
    RandomGenerator rng = Simulation.repeatableRandomGenerator(1234);
    final var simulation = new Simulation(rng, 30);

    // when we run for a maximum of 100 iterations
    simulation.run(100);

    // then we should have a single leader and the rest followers
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();

    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    assertThat(roles.stream().filter(r -> r == TrexRole.LEAD).count()).isEqualTo(1);
  }
}
