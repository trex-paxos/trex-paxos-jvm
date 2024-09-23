package com.github.trex_paxos;


import com.github.trex_paxos.msg.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class Simulation {
  static final Logger LOGGER = Logger.getLogger(Simulation.class.getName());
  private final RandomGenerator rng;
  private final long maxTimeout;

  public Simulation(RandomGenerator rng, long maxTimeout) {
    this.maxTimeout = maxTimeout;
    this.rng = rng;
  }

  public static void main(String[] args) {
    RandomGenerator rng = repeatableRandomGenerator(1234);
    new Simulation(rng, 30).run(100);
  }

  static RandomGenerator repeatableRandomGenerator(long seed) {
    RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of("L64X128MixRandom");
    // Create a seeded random generator using the factory
    RandomGenerator rng = factory.create(seed);
    LOGGER.info("Simulation Using Seed: " + seed);
    return rng;
  }

  Map<Byte,Long> nodeTimeouts = new HashMap<>();

  sealed interface Event permits Timeout, Send {
  }

  record Timeout(byte nodeIdentifier) implements Event {
  }

  record Send(byte from, TrexMessage message) implements Event {
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
    //final var now = eventQueue.isEmpty() ? 0 : eventQueue.firstEntry().getKey();
    final var when = now() + timeout;
    nodeTimeouts.put(nodeIdentifier, when);
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    events.add(new Timeout(nodeIdentifier));
  }

  private void upCall(Command chosenCommand) {
    throw new AssertionError("not implemented");
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

    IntStream.range(0, iterations).forEach(_ -> {
      Optional.ofNullable(eventQueue.pollFirstEntry()).ifPresent(timeWithEvents -> {

        // advance the clock
        tick(timeWithEvents.getKey());

        // grab the events at this time
        final var events = timeWithEvents.getValue();

        // for what is in the queue of events at this time
        final List<TrexMessage> newMessages = events.stream().flatMap(event -> {
          LOGGER.info("\tevent: " + event);
          if (event instanceof Timeout timeout) {
            // if it is a timeout collect the message
            final var prepare = switch (timeout.nodeIdentifier) {
              case 1 -> trexEngine1.timeout();
              case 2 -> trexEngine2.timeout();
              case 3 -> trexEngine3.timeout();
              default -> throw new IllegalStateException("Unexpected value: " + timeout.nodeIdentifier);
            };
            return prepare.stream();
          }
          // if it is a message that has arrived run paxos
          else if (event instanceof Send send) {
            return switch (send.message()) {
              case Prepare m -> engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
              case Accept m -> engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
              case Commit m -> engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
              case PrepareResponse m -> engines.get(m.to()).paxos(m).stream();
              case AcceptResponse m -> engines.get(m.to()).paxos(m).stream();
              case Catchup m -> engines.get(m.to()).paxos(m).stream();
              case CatchupResponse m -> engines.get(m.to()).paxos(m).stream();
            };
          }
          throw new AssertionError("unexpected event: " + event);
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
    });
  }

  private TestablePaxosEngine trexEngine(byte nodeIdentifier) {
    return new TestablePaxosEngine(nodeIdentifier,
        QuorumStrategy.SIMPLE_MAJORITY,
        new TransparentJournal(nodeIdentifier),
        new TestableHostApplication(nodeIdentifier));
  }

  class TestablePaxosEngine extends TrexEngine {

    public TestablePaxosEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal, HostApplication hostApplication) {
      super(new TrexNode(nodeIdentifier, quorumStrategy, journal, hostApplication));
    }

    @Override
    void setRandomTimeout() {
      Simulation.this.setRandomTimeout(trexNode.nodeIdentifier());
    }

    @Override
    void resetTimeout() {
      Simulation.this.resetTimeout(trexNode.nodeIdentifier());
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

    List<Accept> fakeJournal = new ArrayList<>(100);

    @Override
    public void journalAccept(Accept accept) {
      fakeJournal.set((int) accept.logIndex(), accept);
    }

    @Override
    public Progress loadProgress(byte nodeIdentifier) {
      return progress;
    }

    @Override
    public Optional<Accept> loadAccept(long logIndex) {
      return logIndex >= fakeJournal.size() ? Optional.empty() : Optional.ofNullable(fakeJournal.get((int) logIndex));
    }
  }

  static class TestableHostApplication implements HostApplication {

    final byte nodeIdentifier;

    TestableHostApplication(byte nodeIdentifier) {
      this.nodeIdentifier = nodeIdentifier;
    }

    List<Command> chosenCommands = new ArrayList<>(100);

    @Override
    public void upCall(Command chosenCommand) {
      chosenCommands.add(chosenCommand);
    }

    @Override
    public void heartbeat(Commit commit) {
      throw new AssertionError("not implemented");
    }

    @Override
    public void timeout(Prepare prepare) {
      throw new AssertionError("not implemented");
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
    RandomGenerator rng = Simulation.repeatableRandomGenerator(1234);
    final var simulation = new Simulation(rng, 30);
    simulation.run(100);
    final var roles = simulation.engines.values().stream()
        .map(TrexEngine::trexNode)
        .map(TrexNode::currentRole)
        .toList();
    assertThat(roles).containsOnly(TrexRole.FOLLOW, TrexRole.LEAD);
    Simulation.LOGGER.info("done");
  }
}
