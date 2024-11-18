package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

class Simulation {

  static final Logger LOGGER = Logger.getLogger("");

  private final RandomGenerator rng;
  private final long longMaxTimeout;
  private final long shortMaxTimeout;

  public Simulation(RandomGenerator rng, long longMaxTimeout) {
    this.longMaxTimeout = longMaxTimeout;
    this.rng = rng;
    shortMaxTimeout = longMaxTimeout / 3;
    assert this.longMaxTimeout > 1;
    assert this.shortMaxTimeout > 0 && this.shortMaxTimeout < this.longMaxTimeout;
    LOGGER.info("maxTimeout: " + longMaxTimeout + " halfTimeout: " + shortMaxTimeout);
  }

  static RandomGenerator repeatableRandomGenerator(long seed) {
    RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of("L64X128MixRandom");
    // Create a seeded random generator using the factory
    RandomGenerator rng = factory.create(seed);
    LOGGER.info("Simulation Using Seed: " + seed);
    return rng;
  }

  Map<Byte, Long> nodeTimeouts = new HashMap<>();

  public void run(
      int iterations,
      boolean makeClientMessages,
      BiFunction<Send, Long, Stream<TrexMessage>> nemesis
  ) {
    if (makeClientMessages) {
      makeClientDataEvents(iterations, eventQueue);
    }

    final var _ = IntStream.range(0, iterations).anyMatch(iteration -> {
      Optional.ofNullable(eventQueue.pollFirstEntry())
          .ifPresent(timeWithEvents -> {
        // advance the clock
        tick(timeWithEvents.getKey());

        LOGGER.info("------------------");
        LOGGER.info("tick: " + now + "\n\t" + trexEngine1.role() + " " + trexEngine1.trexNode().progress.toString()
            + "\n\t" + trexEngine2.role() + " " + trexEngine2.trexNode().progress.toString()
            + "\n\t" + trexEngine3.role() + " " + trexEngine3.trexNode().progress.toString()
        );

        // grab the events at this time
        final var events = timeWithEvents.getValue();

        // for what is in the queue of events at this time
        final List<TrexMessage> newMessages = events.stream().flatMap(event -> {
          LOGGER.fine(() -> "event: " + event);
          switch (event) {
            case Timeout timeout -> {
              // if it is a timeout collect the prepare messages if the node is still a follower at this time
              final var prepare = switch (timeout.nodeIdentifier) {
                case 1 -> trexEngine1.timeout();
                case 2 -> trexEngine2.timeout();
                case 3 -> trexEngine3.timeout();
                default ->
                    throw new IllegalStateException("Unexpected node identifier for timeout: " + timeout.nodeIdentifier);
              };
              return prepare.stream();
            }

            // if it is a messages that has arrived run paxos
            case Send send -> {
              return networkSimulation(send, now, nemesis);
            }
            case Heartbeat heartbeat -> {
              // if it is a timeout collect the prepare messages if the node is still a follower at this time
              final var commitWithAccepts = switch (heartbeat.nodeIdentifier) {
                case 1 -> trexEngine1.heartbeat();
                case 2 -> trexEngine2.heartbeat();
                case 3 -> trexEngine3.heartbeat();
                default ->
                    throw new IllegalStateException("Unexpected node identifier for heartbeat: " + heartbeat.nodeIdentifier);
              };
              return commitWithAccepts.stream();
            }
            case ClientCommand _ -> {
              return engines.entrySet().stream()
                  .flatMap(e -> {
                    final var commands = IntStream.range(0, 3).mapToObj(i -> {
                      final var data = now + ":" + e.getKey() + i;
                      return new Command(data, data.getBytes());
                    }).toList();

                    final var msg = e.getValue().command(commands);
                    return msg.stream();
                  });
            }
          }
        }).toList();

        // the messages arrive in the next time unit
        if (!newMessages.isEmpty()) {
          LOGGER.fine(() -> "\tnewMessages:\n\t" + newMessages.stream()
              .map(Object::toString)
              .collect(Collectors.joining("\n\t")));

          // messages sent in the cluster will arrive after 1 time unit
          final var nextTime = now + 1;
          // we add the messages to the event queue at that time
          final var nextTimeList = this.eventQueue.computeIfAbsent(nextTime, _ -> new ArrayList<>());
          nextTimeList.add(new Send(newMessages));
        }
      });
      // if the event queue is empty we are done
      var finished = this.eventQueue.isEmpty();
      if (finished) {
        LOGGER.info("finished as empty iteration: " + iteration);
      }
      final var inconsistentCommittedIndex = inconsistentCommittedIndex(
          trexEngine1.allCommandsMap,
          trexEngine2.allCommandsMap,
          trexEngine3.allCommandsMap);
      finished = finished || inconsistentCommittedIndex.isPresent();
      if (inconsistentCommittedIndex.isPresent()) {
        LOGGER.info("finished as not matching commands:" +
            "\n\t" + trexEngine1.allCommands().stream().map(Objects::toString).collect(Collectors.joining(",")) + "\n"
            + "\n\t" + trexEngine2.allCommands().stream().map(Objects::toString).collect(Collectors.joining(",")) + "\n"
            + "\n\t" + trexEngine3.allCommands().stream().map(Objects::toString).collect(Collectors.joining(",")));
        throw new AssertionError("commands not matching");
      }
      boolean commitLengthNotEqualToCommandLength =
          trexEngine1.allCommandsMap.size() != trexEngine1.trexNode.progress.highestCommittedIndex() ||
              trexEngine2.allCommandsMap.size() != trexEngine2.trexNode.progress.highestCommittedIndex() ||
              trexEngine3.allCommandsMap.size() != trexEngine3.trexNode.progress.highestCommittedIndex();
      finished = finished || commitLengthNotEqualToCommandLength;
      if (commitLengthNotEqualToCommandLength) {
        LOGGER.info("finished as commit length not equal to command length:\n" +
            "\t highestAcceptedIndex=" + trexEngine1.trexNode.progress.highestCommittedIndex() + ", commandSize" + trexEngine1.allCommands().size() + "\n" +
            "\t highestAcceptedIndex=" + trexEngine2.trexNode.progress.highestCommittedIndex() + ", commandSize" + trexEngine2.allCommands().size() + "\n" +
            "\t highestAcceptedIndex=" + trexEngine3.trexNode.progress.highestCommittedIndex() + ", commandSize" + trexEngine3.allCommands().size() + "\n"
        );
        throw new AssertionError("commit length not equal to command length");
      }
      return finished;
    });
  }

  static OptionalLong inconsistentCommittedIndex(TreeMap<Long, AbstractCommand> c1,
                                                 TreeMap<Long, AbstractCommand> c2,
                                                 TreeMap<Long, AbstractCommand> c3) {
    final var c1last = !c1.isEmpty() ? c1.lastKey() : 0;
    final var c2last = !c2.isEmpty() ? c2.lastKey() : 0;
    final var c3last = !c3.isEmpty() ? c3.lastKey() : 0;
    final var maxLength = Math.max(
        c1last, Math.max(
            c2last,
            c3last));
    return LongStream.range(0, maxLength).filter(i -> {
      final Optional<AbstractCommand> optC1 = Optional.ofNullable(c1.get(i));
      final Optional<AbstractCommand> optC2 = Optional.ofNullable(c2.get(i));
      final Optional<AbstractCommand> optC3 = Optional.ofNullable(c3.get(i));

      // Check if all non-empty values are equal
      final var result =
          optC1.map(
                  // if one is defined check it against the two and three
                  a1 -> optC2.map(a1::equals).orElse(true) && optC3.map(a1::equals).orElse(true)
              )
              // if one is not defined then check two against three
              .orElse(true)
              &&
              optC2.map(
                  // check two against three
                  a2 -> optC3.map(a2::equals).orElse(true)
              ).orElse(true); // if one and two are not defined it does not matter what three is
      if (!result) {
        LOGGER.info("command mismatch logIndex=" + i + ":\n\t" + optC1 + "\n\t" + optC2 + "\n\t" + optC3);
      }
      return !result;
    }).findFirst();
  }

  public void run(int i, boolean b) {
    run(i, b, DEFAULT_NETWORK_SIMULATION);
  }

  sealed interface Event permits Heartbeat, Send, Timeout, ClientCommand {
  }

  record Timeout(byte nodeIdentifier) implements Event {
  }

  record Send(List<TrexMessage> messages) implements Event {
  }

  record Heartbeat(byte nodeIdentifier) implements Event {
  }

  record ClientCommand() implements Event {
  }

  NavigableMap<Long, List<Event>> eventQueue = new TreeMap<>();

  long now = 0;
  long lastNow = 0;

  private void tick(long now) {
    this.lastNow = this.now;
    this.now = now;
  }

  private void setTimeout(byte nodeIdentifier) {
    final var oldTimeouts = new Long[]{nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier)};
    final var timeout = rng.nextInt((int) shortMaxTimeout + 1, (int) longMaxTimeout);
    final var when = Math.max(lastNow, now) + timeout;
    if (nodeTimeouts.containsKey(nodeIdentifier)) {
      clearTimeout(nodeIdentifier);
    }
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    nodeTimeouts.put(nodeIdentifier, when);
    events.add(new Timeout(nodeIdentifier));
    final var newTimeouts = new Long[]{nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier)};
    LOGGER.fine(() -> "\tsetTimeout: " + Arrays.toString(oldTimeouts) + " -> " + Arrays.toString(newTimeouts) + " : " + nodeIdentifier + "+=" + timeout);
  }

  private void clearTimeout(byte nodeIdentifier) {
    final var oldTimeouts = new Long[]{nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier)};
    Optional.ofNullable(nodeTimeouts.get(nodeIdentifier)).ifPresent(timeout -> Optional.ofNullable(eventQueue.get(timeout)).ifPresent(events -> {
          events.remove(new Timeout(nodeIdentifier));
          if (events.isEmpty()) {
            eventQueue.remove(timeout);
          }
        })
    );
    nodeTimeouts.remove(nodeIdentifier);
    final var newTimeouts = new Long[]{nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier), nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier)};
    LOGGER.fine(() -> "\tclearTimeout: " + Arrays.toString(oldTimeouts) + " -> " + Arrays.toString(newTimeouts) + " : " + nodeIdentifier);
  }

  private void setHeartbeat(byte nodeIdentifier) {
    final var timeout = rng.nextInt((int) shortMaxTimeout / 2, (int) shortMaxTimeout);
    final var when = Math.max(lastNow, now) + timeout; // TODO no need to max?
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    final var hb = new Heartbeat(nodeIdentifier);
    if (!events.contains(hb)) {
      events.add(hb);
      LOGGER.fine(() -> "\tsetHeartbeat: " + nodeIdentifier + "+=" + timeout);
    }
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  final TestablePaxosEngine trexEngine1 = makeTrexEngine((byte) 1, threeNodeQuorum);
  final TestablePaxosEngine trexEngine2 = makeTrexEngine((byte) 2, threeNodeQuorum);
  final TestablePaxosEngine trexEngine3 = makeTrexEngine((byte) 3, threeNodeQuorum);

  final Map<Byte, TestablePaxosEngine> engines = Map.of(
      (byte) 1, trexEngine1,
      (byte) 2, trexEngine2,
      (byte) 3, trexEngine3
  );

  // start will launch some timeouts into the event queue
  void coldStart() {
    trexEngine1.start();
    trexEngine2.start();
    trexEngine3.start();
  }

  /// This is how we can inject network failures into the simulation. The default implementation is to pass the messages
  /// without any errors. It is intended that this method is override with a method that will simulate network failures.
  /// This sort of testing is inspired by the Jepsen testing framework which calls the errors a Nemesis.
  ///
  /// @param send The messages to simulate sending.
  /// @param now  The current time in the simulation.
  /// @return The messages that will be sent to the network having been interfered with to simulate network failures.
  protected Stream<TrexMessage> networkSimulation(Send send, Long now, BiFunction<Send, Long, Stream<TrexMessage>> nemesis) {
    return nemesis.apply(send, now);
  }

  final BiFunction<Send, Long, Stream<TrexMessage>> DEFAULT_NETWORK_SIMULATION =
      (send, _) ->
          send.messages.stream().flatMap(x -> switch (x) {
            case BroadcastMessage m -> engines.values().stream().flatMap(engine -> engine.paxos(m).messages().stream());
            case DirectMessage m -> engines.get(m.to()).paxos(m).messages().stream();
          });

  private void makeClientDataEvents(int iterations, NavigableMap<Long, List<Event>> eventQueue) {
    IntStream.range(0, iterations).forEach(i -> {
      if (rng.nextBoolean()) {
        eventQueue.put((long) i, new ArrayList<>(List.of(new ClientCommand())));
      }
    });
  }

  TestablePaxosEngine makeTrexEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy) {
    return new TestablePaxosEngine(nodeIdentifier,
        quorumStrategy,
        new TransparentJournal(nodeIdentifier)
    );
  }

  class TestablePaxosEngine extends TrexEngine {

    final TransparentJournal journal;

    final TreeMap<Long, AbstractCommand> allCommandsMap = new TreeMap<>();

    public List<AbstractCommand> allCommands() {
      return new ArrayList<>(allCommandsMap.values());
    }

    public TestablePaxosEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, TransparentJournal journal) {
      super(new TrexNode(Level.INFO, nodeIdentifier, quorumStrategy, journal));
      this.journal = journal;
    }

    @Override
    protected void setRandomTimeout() {
      Simulation.this.setTimeout(trexNode.nodeIdentifier());
    }

    @Override
    protected void clearTimeout() {
      Simulation.this.clearTimeout(trexNode.nodeIdentifier);
    }

    @Override
    protected void setHeartbeat() {
      Simulation.this.setHeartbeat(trexNode.nodeIdentifier);
    }

    TrexResult paxos(TrexMessage input) {
      if (input.from() == trexNode.nodeIdentifier()) {
        return TrexResult.noResult();
      }
      LOGGER.finer(() -> trexNode.nodeIdentifier + " <~ " + input);
      final var oldRole = trexNode.getRole();
      final var result = super.paxosNotThreadSafe(input);
      final var newRole = trexNode.getRole();
      if (oldRole != newRole) {
        LOGGER.info(() -> "Node has changed role:" + trexNode.nodeIdentifier() + " == " + newRole);
      }
      if (!result.commands().isEmpty()) {
        allCommandsMap.putAll(result.commands());
      }
      return result;
    }

    @Override
    public String toString() {
      return "PaxosEngine{" +
          trexNode.nodeIdentifier() + "=" +
          trexNode.currentRole().toString() + "," +
          trexNode.progress +
          '}';
    }

    public String role() {
      return trexNode.currentRole().toString();
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

    @Override
    public void sync() {
      // no-op
    }
  }
}
