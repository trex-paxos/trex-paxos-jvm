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

import static com.github.trex_paxos.Simulation.LOGGER;
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

  sealed interface Event permits Heartbeat, Send, Timeout, ClientCommand {
  }

  record Timeout(byte nodeIdentifier) implements Event {
  }

  record Send(Message message) implements Event {
  }

  record Heartbeat(byte nodeIdentifier) implements Event {
  }

  record ClientCommand() implements Event {
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

  private long tick(long now) {
    this.now = now;
    LOGGER.info("\ttick: " + now);
    return now;
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

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  final TestablePaxosEngine trexEngine1 = trexEngine((byte) 1, threeNodeQuorum);
  final TestablePaxosEngine trexEngine2 = trexEngine((byte) 2, threeNodeQuorum);
  final TestablePaxosEngine trexEngine3 = trexEngine((byte) 3, threeNodeQuorum);

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

  ArrayList<Message> run(int iterations, boolean clientData) {
    final var allMessages = new ArrayList<Message>();

    if (clientData) {
      makeClientDataEvents(iterations, eventQueue);
    }

    final var _ = IntStream.range(0, iterations).anyMatch(i -> {
      Optional.ofNullable(eventQueue.pollFirstEntry()).ifPresent(timeWithEvents -> {

        // advance the clock
        final var now = tick(timeWithEvents.getKey());

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
                case BroadcastMessage m -> engines.values().stream()
                    .flatMap(engine -> engine.paxos(m).messages().stream());
                case DirectMessage m -> engines.get(m.to()).paxos(m).messages().stream();
                case AbstractCommand abstractCommand ->
                    throw new AssertionError("Unexpected command message: " + abstractCommand);
              };
            }
            case Heartbeat heartbeat -> {
              // if it is a timeout collect the prepare message if the node is still a follower at this time
              final var commit = switch (heartbeat.nodeIdentifier) {
                case 1 -> trexEngine1.heartbeat();
                case 2 -> trexEngine2.heartbeat();
                case 3 -> trexEngine3.heartbeat();
                default ->
                    throw new IllegalStateException("Unexpected node identifier for heartbeat: " + heartbeat.nodeIdentifier);
              };
              return commit.stream();
            }
            case ClientCommand _ -> {
              return engines.entrySet().stream()
                  .flatMap(e -> {
                    final var data = now + ":" + e.getKey();
                    final var msg = e.getValue().command(
                        new Command(data, data.getBytes()));
                    return msg.stream();
                  });
            }
          }
        }).toList();

        // the message arrive in the next time unit
        if( !newMessages.isEmpty() ){
          LOGGER.info("\t\tnewMessages:\n\t" + newMessages.stream()
              .map(Object::toString)
              .collect(Collectors.joining("\n\t")));

          // messages sent in the cluster will arrive after 1 time unit
          final var nextTime = now() + 1;
          // we add the messages to the event queue at that time
          final var nextTimeList = this.eventQueue.computeIfAbsent(nextTime, _ -> new ArrayList<>());
          nextTimeList.addAll(newMessages.stream().map(Send::new).toList());
        }

        // add the messages to the all messages list
        allMessages.addAll(newMessages);
      });
      // if the event queue is empty we are done
      var finished = this.eventQueue.isEmpty();
      if (finished) {
        LOGGER.info("finished as no on iteration: " + i);
      }
      return finished;
    });
    return allMessages;
  }

  private void makeClientDataEvents(int iterations, NavigableMap<Long, List<Event>> eventQueue) {
    IntStream.range(0, iterations).forEach(i -> {
      if (rng.nextBoolean()) {
        eventQueue.put((long) i, new ArrayList<>(List.of(new ClientCommand())));
      }
    });
  }

  private TestablePaxosEngine trexEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy) {
    return new TestablePaxosEngine(nodeIdentifier,
        quorumStrategy,
        new TransparentJournal(nodeIdentifier)
    );
  }

  class TestablePaxosEngine extends TrexEngine {

    final TransparentJournal journal;

    public TestablePaxosEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, TransparentJournal journal) {
      super(new TrexNode(nodeIdentifier, quorumStrategy, journal));
      this.journal = journal;
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
    void setHeartbeat() {
      Simulation.this.setHeartbeat(trexNode.nodeIdentifier);
    }

    @Override
    public TrexResult paxos(TrexMessage input) {
      final var oldRole = trexNode.getRole();
      final var result = super.paxos(input);
      final var newRole = trexNode.getRole();
      if (oldRole != newRole) {
        LOGGER.info("Role change: " + trexNode.nodeIdentifier() + " " + oldRole + " -> " + newRole);
      }
      return result;
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
  public void testLeaderElection1000() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(1234);
    IntStream.range(0, 1000).forEach(i -> {
          LOGGER.info("\n --------------- \nstarting iteration: " + i);
      testLeaderElection(rng);
        }
    );
  }

  public void testLeaderElection(RandomGenerator rng) {
    // given a repeatable test setup
    final var simulation = new Simulation(rng, 30);

    // we do a cold cluster start with no prior leader in the journals
    simulation.coldStart();

    // when we run for a maximum of 10 iterations
    final var messages = simulation.run(10, false);

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
  public void testClientWork() {
    RandomGenerator rng = Simulation.repeatableRandomGenerator(456789L);

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
    assertThat(simulation.trexEngine1.journal.fakeJournal)
        .isEqualTo(simulation.trexEngine2.journal.fakeJournal)
        .isEqualTo(simulation.trexEngine3.journal.fakeJournal);
  }

  private void makeLeader(Simulation simulation) {

    final var leader = simulation.trexEngine1;

    // timing out the leader will set its timeout so no need to call start here
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
