package com.github.trex_paxos;


import com.github.trex_paxos.msg.*;
import org.assertj.core.api.Assert;

import java.util.*;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;


public class  Simulations {

  private final RandomGenerator rng;
  private final long maxTimeout;

  public Simulations(RandomGenerator rng, long maxTimeout) {
    this.maxTimeout = maxTimeout;
    this.rng = rng;
  }

  public static void main(String[] args) {
    RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of("L64X128MixRandom");
    // Create a seeded random generator using the factory
    RandomGenerator rng = factory.create(1234);
    new Simulations(rng, 30).run(100);
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

  private void run(int iterations) {

    // start will launch some timeouts into the event queue
    trexEngine1.start();
    trexEngine2.start();
    trexEngine3.start();

    IntStream.range(0, iterations).forEach(_ -> {
      // grab the events at the next time spot
      final var  timeWithEvents = eventQueue.pollFirstEntry();

      // advance the clock
      tick(timeWithEvents.getKey());

      // grab the events at this time
      final var events = timeWithEvents.getValue();

      // for what is in the queue of events at thit time
      final var newMessages = events.stream().flatMap(event -> {
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
            case Prepare m ->
                engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
            case Accept m ->
                engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
            case Commit m ->
                engines.values().stream().flatMap(engine -> engine.paxos(m).stream());
            case PrepareResponse m ->
                engines.get(m.to()).paxos(m).stream();
            case AcceptResponse m ->
                engines.get(m.to()).paxos(m).stream();
            case Catchup m ->
                engines.get(m.to()).paxos(m).stream();
            case CatchupResponse m ->
                engines.get(m.to()).paxos(m).stream();
          };
        }
        throw new AssertionError("unexpected event: "+event);
      });
      // messages sent in the cluster will arrive after 1 time unit
      final var nextTime = now() + 1;
      // we add the messages to the event queue at that time
      this.eventQueue.getOrDefault(nextTime, new ArrayList<>())
          .addAll(newMessages.map(m -> new Send(m.from(), m))
              .toList());
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
      Simulations.this.setRandomTimeout(trexNode.nodeIdentifier());
    }

    @Override
    void resetTimeout() {
      Simulations.this.resetTimeout(trexNode.nodeIdentifier());
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

