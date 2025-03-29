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

import com.github.trex_paxos.msg.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.github.trex_paxos.TrexLogger.LOGGER;

class SimulationFPaxos {
  private final RandomGenerator rng;
  private final long longMaxTimeout;
  private final long shortMaxTimeout;

  public SimulationFPaxos(RandomGenerator rng, long longMaxTimeout) {
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

  Map<Short, Long> nodeTimeouts = new HashMap<>();

  Optional<Prepare> timeout(TrexNode trexNode) {
    var result = trexNode.timeout();
    if (result.isPresent()) {
      LOGGER.fine(() -> "Timeout: " + trexNode.nodeIdentifier() + " " + trexNode.getRole());
      setRandomTimeout(trexNode.nodeIdentifier());
    }
    return result;
  }

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
                + "\n\t" + trexEngine4.role() + " " + trexEngine4.trexNode().progress.toString()
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
                    case 1 -> timeout(trexEngine1.trexNode());
                    case 2 -> timeout(trexEngine2.trexNode());
                    case 3 -> timeout(trexEngine3.trexNode());
                    case 4 -> timeout(trexEngine4.trexNode());
                    default ->
                        throw new IllegalArgumentException("Unexpected node identifier for timeout: " + timeout.nodeIdentifier);
                  };
                  return prepare.stream();
                }

                // if it is a messages that has arrived run paxos after we have reset timeouts
                case Send send -> {
                  return networkSimulation(send, now, nemesis);
                }
                case Heartbeat heartbeat -> {
                  // if it is a timeout collect the prepare messages if the node is still a follower at this time
                  final var fixedWithAccepts = switch (heartbeat.nodeIdentifier) {
                    case 1 -> createHeartbeatMessagesAndReschedule(trexEngine1.trexNode());
                    case 2 -> createHeartbeatMessagesAndReschedule(trexEngine2.trexNode());
                    case 3 -> createHeartbeatMessagesAndReschedule(trexEngine3.trexNode());
                    case 4 -> createHeartbeatMessagesAndReschedule(trexEngine4.trexNode());
                    default ->
                        throw new IllegalArgumentException("Unexpected node identifier for heartbeat: " + heartbeat.nodeIdentifier);
                  };
                  return fixedWithAccepts.stream();
                }
                case ClientCommand _ -> {
                  return engines.entrySet().stream()
                      .flatMap(e -> {
                        final var commands = IntStream.range(0, 3).mapToObj(i -> {
                          final var data = now + ":" + e.getKey() + i;
                          return new Command(data.getBytes());
                        }).toList();
                        final TrexEngine<?> engine = e.getValue();
                        return engine.isLeader() ? engine.nextLeaderBatchOfMessages(commands).stream()
                            : Stream.empty();
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
      final var inconsistentFixedIndex = inconsistentFixedIndex2(
          allCommandsMap.get(trexEngine1.nodeIdentifier()),
          allCommandsMap.get(trexEngine2.nodeIdentifier()),
          allCommandsMap.get(trexEngine3.nodeIdentifier()),
          allCommandsMap.get(trexEngine4.nodeIdentifier())
      );
      finished = finished || inconsistentFixedIndex.isPresent();
      if (inconsistentFixedIndex.isPresent()) {
        LOGGER.info("finished as not matching results:" +
            "\n\t" + allCommandsMap.get(trexEngine1.nodeIdentifier()).values().stream().map(Objects::toString).collect(Collectors.joining(",")) + "\n"
            + "\n\t" + allCommandsMap.get(trexEngine2.nodeIdentifier()).values().stream().map(Objects::toString).collect(Collectors.joining(",")) + "\n"
            + "\n\t" + allCommandsMap.get(trexEngine3.nodeIdentifier()).values().stream().map(Objects::toString).collect(Collectors.joining(","))+ "\n"
            + "\n\t" + allCommandsMap.get(trexEngine4.nodeIdentifier()).values().stream().map(Objects::toString).collect(Collectors.joining(","))
        );
        throw new AssertionError("results not matching");
      }
      engines.values().forEach(
          engine -> engine.journal.fakeJournal.forEach((k, v) -> {
            if (!v.slot().equals(k)) {
              throw new AssertionError("Journaled accept.logIndex not equal to slot index");
            }
          })
      );
      return finished;
    });
  }

  List<TrexMessage> createHeartbeatMessagesAndReschedule(TrexNode trexNode) {
    var result = trexNode.createHeartbeatMessages();
    if (!result.isEmpty()) {
      setHeartbeat(trexNode.nodeIdentifier());
      LOGGER.finer(() -> "Heartbeat: " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + result);
    }
    return result;
  }

  static OptionalLong inconsistentFixedIndex2(TreeMap<Long, Command> c1,
                                             TreeMap<Long, Command> c2,
                                             TreeMap<Long, Command> c3,
                                             TreeMap<Long, Command> c4
                                             ) {
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
              a1 -> optC2.map(a1::equals).orElse(true) &&
                  optC3.map(a1::equals).orElse(true) &&
                  optC4.map(a1::equals).orElse(true)
          ).orElse(true) &&
              optC2.map(
                  a2 -> optC3.map(a2::equals).orElse(true) &&
                      optC4.map(a2::equals).orElse(true)
              ).orElse(true) &&
              optC3.map(
                  a3 -> optC4.map(a3::equals).orElse(true)
              ).orElse(true);
      if (!result) {
        LOGGER.info("command mismatch logIndex=" + i + ":\n\t" + optC1 + "\n\t" + optC2 + "\n\t" + optC3);
      }
      return !result;
    }).findFirst();
  }

  public void run(int iterations, boolean makeClientMessages) {
    run(iterations, makeClientMessages, DEFAULT_NETWORK_SIMULATION);
  }

  sealed interface Event permits Heartbeat, Send, Timeout, ClientCommand {
  }

  record Timeout(short nodeIdentifier) implements Event {
  }

  record Send(List<TrexMessage> messages) implements Event {
  }

  record Heartbeat(short nodeIdentifier) implements Event {
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

  void setRandomTimeout(short nodeIdentifier) {
    final var oldTimeouts = new Long[]
        {nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier),
            nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier),
            nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier),
            nodeTimeouts.get(trexEngine4.trexNode.nodeIdentifier)
        };
    final var timeout = rng.nextInt((int) shortMaxTimeout + 1, (int) longMaxTimeout - 1);
    final var when = Math.max(lastNow, now) + timeout;
    if (nodeTimeouts.containsKey(nodeIdentifier)) {
      clearTimeout(nodeIdentifier);
    }
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    nodeTimeouts.put(nodeIdentifier, when);
    events.add(new Timeout(nodeIdentifier));
    final var newTimeouts = new Long[]{nodeTimeouts.get(
        trexEngine1.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine4.trexNode.nodeIdentifier)
    };
    LOGGER.fine(() -> "\tsetRandomTimeout: " + Arrays.toString(oldTimeouts) + " -> " + Arrays.toString(newTimeouts) + " : " + nodeIdentifier + "+=" + timeout);
  }

  void clearTimeout(short nodeIdentifier) {
    final var oldTimeouts = new Long[]{
        nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine4.trexNode.nodeIdentifier)
    };
    Optional.ofNullable(nodeTimeouts.get(nodeIdentifier)).ifPresent(timeout -> Optional.ofNullable(eventQueue.get(timeout)).ifPresent(events -> {
          events.remove(new Timeout(nodeIdentifier));
          if (events.isEmpty()) {
            eventQueue.remove(timeout);
          }
        })
    );
    nodeTimeouts.remove(nodeIdentifier);
    final var newTimeouts = new Long[]{
        nodeTimeouts.get(trexEngine1.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine2.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine3.trexNode.nodeIdentifier),
        nodeTimeouts.get(trexEngine4.trexNode.nodeIdentifier)
    };
    LOGGER.fine(() -> "\tclearTimeout: " + Arrays.toString(oldTimeouts) + " -> " + Arrays.toString(newTimeouts) + " : " + nodeIdentifier);
  }

  void setHeartbeat(short nodeIdentifier) {
    final var timeout = rng.nextInt((int) shortMaxTimeout / 2, (int) shortMaxTimeout);
    final var when = Math.max(lastNow, now) + timeout;
    final var events = eventQueue.computeIfAbsent(when, _ -> new ArrayList<>());
    final var hb = new Heartbeat(nodeIdentifier);
    if (!events.contains(hb)) {
      events.add(hb);
      LOGGER.fine(() -> "\tsetHeartbeat: " + nodeIdentifier + "+=" + timeout);
    }
  }

  final QuorumStrategy threeNodeQuorum = new FlexiblePaxosQuorum(
      Set.of(
          new VotingWeight((short) 1, 1),
          new VotingWeight((short) 2, 1),
          new VotingWeight((short) 3, 1),
          new VotingWeight((short) 4, 1)
      ),
      3,
      2
      );

  final Map<Short, TreeMap<Long, Command>> allCommandsMap = commandMaps();

  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private Map<Short, TreeMap<Long, Command>> commandMaps() {
    final var c1 = new TreeMap<Long, Command>();
    final var c2 = new TreeMap<Long, Command>();
    final var c3 = new TreeMap<Long, Command>();
    final var c4 = new TreeMap<Long, Command>();
    return Map.of(
        (short) 1, c1,
        (short) 2, c2,
        (short) 3, c3,
        (short) 4, c4
    );
  }

  final SimulationPaxosEngine<Command> trexEngine1 = makeTrexEngine((short) 1, threeNodeQuorum, allCommandsMap.get((short) 1));
  final SimulationPaxosEngine<Command> trexEngine2 = makeTrexEngine((short) 2, threeNodeQuorum, allCommandsMap.get((short) 2));
  final SimulationPaxosEngine<Command> trexEngine3 = makeTrexEngine((short) 3, threeNodeQuorum, allCommandsMap.get((short) 3));
  final SimulationPaxosEngine<Command> trexEngine4 = makeTrexEngine((short) 4, threeNodeQuorum, allCommandsMap.get((short) 4));

  final Map<Short, SimulationPaxosEngine<Command>> engines = Map.of(
      (short) 1, trexEngine1,
      (short) 2, trexEngine2,
      (short) 3, trexEngine3,
      (short) 4, trexEngine4
  );

  // start will launch some timeouts into the event queue
  void coldStart() {
    setRandomTimeout(trexEngine1.nodeIdentifier());
    setRandomTimeout(trexEngine2.nodeIdentifier());
    setRandomTimeout(trexEngine3.nodeIdentifier());
    setRandomTimeout(trexEngine4.nodeIdentifier());
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
            case BroadcastMessage m ->
                engines.values().stream().flatMap(engine -> engine.paxos(List.of(m)).messages().stream());
            case DirectMessage m -> engines.get(m.to()).paxos(List.of(m)).messages().stream();
          });

  private void makeClientDataEvents(int iterations, NavigableMap<Long, List<Event>> eventQueue) {
    IntStream.range(0, iterations).forEach(i -> {
      if (rng.nextBoolean()) {
        eventQueue.put((long) i, new ArrayList<>(List.of(new ClientCommand())));
      }
    });
  }

  class SimulationPaxosEngine<RESULT> extends TestablePaxosEngine<RESULT> {

    public SimulationPaxosEngine(short nodeIdentifier,
                                 QuorumStrategy quorumStrategy,
                                 TransparentJournal transparentJournal,
                                 BiFunction<Long, Command, RESULT> commitHandler
    ) {
      super(nodeIdentifier, quorumStrategy, transparentJournal, commitHandler);
    }

    @Override
    public EngineResult<RESULT> paxos(List<TrexMessage> trexMessages) {
      final var fixed = trexMessages.stream()
          .filter(m -> m instanceof Fixed)
          .map(m -> (Fixed) m)
          .toList();
      if (!fixed.isEmpty()) {
        LOGGER.finer(() -> "Fixed so setRandomTimeout : " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + fixed);
        setRandomTimeout(trexNode.nodeIdentifier());
      }
      final var oldRole = trexNode.getRole();
      final var result = super.paxos(trexMessages);
      final var newRole = trexNode.getRole();
      if (oldRole != newRole && newRole == TrexNode.TrexRole.LEAD) {
        LOGGER.info(() -> "Node has become leader " + trexNode.nodeIdentifier());
        SimulationFPaxos.this.setHeartbeat(trexNode.nodeIdentifier());
      }
      return result;
    }
  }

  <T> SimulationPaxosEngine<T> makeTrexEngine(short nodeIdentifier, QuorumStrategy quorumStrategy, final TreeMap<Long, Command> allCommands) {
    return new SimulationPaxosEngine<>(nodeIdentifier,
        quorumStrategy,
        new TransparentJournal(nodeIdentifier),
        // Here we have no application callback as we are simply testing that the logs match
        (i, c) -> {
          LOGGER.fine(() -> "i=" + i + " c=" + c);
          allCommands.put(i, c);
          return null;
        }
    );
  }
}
