package com.github.trex_paxos;


import com.github.trex_paxos.msg.*;

import java.util.*;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;


public class Simulations {

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
    new Simulations(rng, 30).run();
  }

  Map<Byte,Long> nodeTimeouts = new HashMap<>();

  sealed interface Event permits Timeout {
  }

  record Timeout(byte nodeIdentifier) implements Event {
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

  private void setRandomTimeout(byte nodeIdentifier) {
    final var timeout = rng.nextInt((int) maxTimeout);
    final var now = eventQueue.isEmpty() ? 0 : eventQueue.firstEntry().getKey();
    final var when = now + timeout;
    nodeTimeouts.put(nodeIdentifier, when);
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    events.add(new Timeout(nodeIdentifier));
  }

  private void send(TrexMessage msg) {
    throw new AssertionError("not implemented");
  }

  private void upCall(Command chosenCommand) {
    throw new AssertionError("not implemented");
  }

  private void run() {
    TrexEngine trexEngine1 = trexEngine((byte) 1);
    TrexEngine trexEngine2 = trexEngine((byte) 2);
    TrexEngine trexEngine3 = trexEngine((byte) 3);

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

  class TestableHostApplication implements HostApplication {

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
      Simulations.this.send(commit);
    }

    @Override
    public void timeout(Prepare prepare) {
      Simulations.this.send(prepare);
    }
  }

}
