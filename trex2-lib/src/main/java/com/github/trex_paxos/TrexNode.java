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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.github.trex_paxos.TrexRole.*;

/// A TrexNode is a single node in a Paxos cluster. It runs the part-time parliament algorithm. It requires
/// collaborating classes.
/// - A [Journal] which must be crash durable storage. The wrapping [TrexEngine] must flush the journal to durable state (fsync) before sending out any messages.
/// - A [QuorumStrategy] which may be a simple majority, in the future FPaxos or UPaxos.
public class TrexNode {
  static final Logger LOGGER = Logger.getLogger("");

  private final Level logAtLevel;

  private boolean crashed = false;

  /// Create a new TrexNode that will load the current progress from the journal. The journal must have been pre-initialised.
  ///
  /// @param nodeIdentifier The unique node identifier. This must be unique across the cluster and across enough time for prior messages to have been forgotten.
  /// @param quorumStrategy The quorum strategy that may be a simple majority, else things like FPaxos or UPaxos
  /// @param journal        The durable storage and durable log. This must be pre-initialised.
  public TrexNode(Level logAtLevel, byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.progress = journal.readProgress(nodeIdentifier);
    if (progress.nodeIdentifier() != nodeIdentifier) {
      LOGGER.severe("FATAL SEVERE ERROR refusing to run the journal state nodeIdentifier does not match this node: nodeIdentifier=" + nodeIdentifier + ", journal.progress.nodeIdentifier=" + progress.nodeIdentifier());
      throw new IllegalArgumentException("nodeIdentifier=" + nodeIdentifier + " progress.nodeIdentifier=" + progress.nodeIdentifier());
    }
    this.logAtLevel = logAtLevel;
  }

  /// The current node identifier. This must be globally unique in the cluster. You can manage that using Paxos itself.
  final byte nodeIdentifier;

  /// The durable storage and durable log.
  final Journal journal;

  // The quorum strategy that may be trivial or may be cluster membership aware to implement UPaxos. You can manage that using Paxos itself.
  final QuorumStrategy quorumStrategy;

  /// If we have rebooted then we start off as a follower.
  /// This is only package private to allow unit tests to set the role.
  TrexRole role = FOLLOW;

  /// The initial progress is loaded from the Journal at startup. It is the last known state of the node prior to a crash.
  Progress progress;

  /// During a recovery we will track all the slots that we are probing to find the highest accepted operationBytes.
  final NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  /// When leading we will track the responses to a stream of accept messages.
  final NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /// The term of a node is the value that it will use with either the next `prepare` or `accept` message.
  /// It is only used by the leader and recoverer. It will be null for a follower.
  BallotNumber term = null;

  /// This is the main Paxos Algorithm. It is not public as a TrexEngine will wrap this to handle specifics of resetting
  /// timeouts. This method will recurse without returning when we need to send a message to ourselves. Possible side
  /// effects:
  ///
  /// * The progress record may be updated in memory and saved into the journal.
  /// * Accept messages may be written into the journal.
  ///
  /// VERY IMPORTANT: The journal *must* be flushed to durable storage before sending out any messages returned from
  /// this method. That ultimately inhibits latency yet batching can be used to maintain throughput. Yet it cannot be
  /// skipped without breaking the algorithm.
  ///
  /// This method suppresses warnings about finally clauses that do not terminate normally so that it can check for
  /// protocol violations and throw in the finally block. We will use jul logging to log any exception thrown in the
  /// main code. The most likely reason to catch an error is that the journal has a problem such as an IOError. Yet
  /// we will log then suppress that exception in order to run the finally block to check for protocol violations.
  ///
  /// @param input The message to process.
  /// @return A possibly empty list of messages to send out to the cluster plus a possibly empty list of chosen commands to up-call to the host
  /// application.
  /// @throws AssertionError if the protocol invariants are violated. This is a none recoverable error. The process should be rebooted so that
  /// only what is in the durable journal is used as the state of this node.
  @SuppressWarnings("Finally")
  TrexResult paxos(TrexMessage input) {
    if (crashed) {
      LOGGER.severe(CRASHED);
      throw new AssertionError(CRASHED);
    }
    List<TrexMessage> messages = new ArrayList<>();
    TreeMap<Long, AbstractCommand> commands = new TreeMap<>();
    final var priorProgress = progress;
    try {
      algorithm(input, messages, commands);
    } catch (Throwable e) {
      // The most probable reason to throw is an IOError from the journal. So we must kill ourselves!
      crashed = true;
      // Log that we are crashing and log the reason.
      LOGGER.severe(CRASHING + e);
      // We will most likely throw a protocol violation error in the finally block. So here we log the exception to stderr as a last resort.
      //noinspection CallToPrintStackTrace
      e.printStackTrace();
    } finally {
      if (priorProgress != progress && !priorProgress.equals(progress)) {
        // The general advice is not to throw. In this case the general advice is wrong.
        // We must throw as we have violated the protocol and that should be seen as fatal.
        validateProtocolInvariantElseThrowError(input, priorProgress);
      }
      if (!commands.isEmpty()) {
        // TODO should we make this run all the time
        assert commands.lastKey() == progress.highestFixedIndex();
      }
    }
    return new TrexResult(messages, commands);
  }

  private void algorithm(TrexMessage input, List<TrexMessage> messages, TreeMap<Long, AbstractCommand> commands) {
    switch (input) {
      case Accept accept -> {
        if (lowerAccept(accept) || fixedSlot(accept)) {
          messages.add(nack(accept.slotTerm()));
        } else if (equalOrHigherAccept(accept)) {
          // always journal first
          journal.writeAccept(accept);
          if (higherAccept(accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            this.progress = progress.withHighestPromised(accept.number());
            // we must change our own vote if we are an old leader
            if (this.role == LEAD) {
              // does this change our prior self vote?
              final var logIndex = accept.logIndex();
              Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex))
                  .ifPresent(acceptVotes -> {
                    final var oldNumber = acceptVotes.accept().number();
                    final var newNumber = accept.number();
                    if (oldNumber.lessThan(newNumber)) {
                      // we have accepted a higher accept which is a promise as per https://stackoverflow.com/a/29929052
                      acceptVotes.responses().put(nodeIdentifier(), nack(acceptVotes.accept()));
                      Set<AcceptResponse.Vote> vs = acceptVotes.responses().values().stream()
                          .map(AcceptResponse::vote).collect(Collectors.toSet());
                      final var quorumOutcome =
                          quorumStrategy.assessAccepts(logIndex, vs);
                      if (quorumOutcome == QuorumOutcome.LOSE) {
                        // this happens in a three node cluster when an isolated split brain leader rejoins
                        abdicate(messages);
                      }
                    }
                  });
            }
          }
          journal.writeProgress(this.progress);
          final var ack = ack(accept);
          if (accept.number().nodeIdentifier() == nodeIdentifier) {
            // we vote for ourself
            paxos(ack);
          }
          messages.add(ack);
        } else {
          assert false;
        }
      }
      case Prepare prepare -> {
        var number = prepare.number();
        if (number.lessThan(progress.highestPromised()) || prepare.logIndex() <= progress.highestFixedIndex()) {
          // nack a low nextPrepareMessage else any nextPrepareMessage for a fixed slot sending any accepts they are missing
          messages.add(nack(prepare));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher nextPrepareMessage
          final var newProgress = progress.withHighestPromised(prepare.number());
          journal.writeProgress(newProgress);
          final var ack = ack(prepare);
          messages.add(ack);
          this.progress = newProgress;
          // leader or recoverer should give way to a higher nextPrepareMessage
          if (prepare.number().nodeIdentifier() != nodeIdentifier && role != FOLLOW) {
            abdicate(messages);
          }
          // we vote for ourself
          if (prepare.number().nodeIdentifier() == nodeIdentifier) {
            paxos(ack);
          }
        } else if (number.equals(progress.highestPromised())) {
          messages.add(ack(prepare));
        } else {
          assert false;
        }
      }
      case AcceptResponse acceptResponse -> {
        if (FOLLOW != role && acceptResponse.to() == nodeIdentifier) {
          // An isolated leader rejoining must back down
          if (LEAD == role && acceptResponse.highestFixedIndex() > progress.highestFixedIndex()) {
            abdicate(messages);
          } else {
            // Both Leader and Recoverer can receive AcceptResponses
            processAcceptResponse(acceptResponse, commands, messages);
          }
        }
      }
      case PrepareResponse prepareResponse -> {
        if (RECOVER == role && prepareResponse.to() == nodeIdentifier) {
          processPrepareResponse(prepareResponse, messages);
        }
      }
      case Fixed(final var fixedFrom, final var fixedSlot, final var fixedNumber) -> {
        if (fixedSlot == highestFixed() + 1) {
          // we must have the correct number at the slot
          final var fixedAccept = journal.readAccept(fixedSlot)
              .filter(accept -> accept.number().equals(fixedNumber))
              .orElse(null);

          // make the callback to the main application
          Optional.ofNullable(fixedAccept).ifPresent(accept -> {
            fixed(accept, commands);
            progress = progress.withHighestFixed(fixedSlot);
            journal.writeProgress(progress);
            if (!role.equals(FOLLOW)) {
              abdicate(messages);
            }
          });
        }

        // if we have not fixed the slot then we must catch up
        final var highestFixedIndex = progress.highestFixedIndex();

        if (fixedSlot > highestFixedIndex) {
          messages.add(new Catchup(nodeIdentifier, fixedFrom, highestFixedIndex, progress.highestPromised()));
        }
      }
      case Catchup(final byte replyTo, _, final var otherFixedIndex, final var otherHighestPromised) -> {
        // load the slots they do not know that they are missing
        final var missingAccepts = LongStream.rangeClosed(otherFixedIndex + 1, progress.highestFixedIndex())
            .mapToObj(journal::readAccept)
            .flatMap(Optional::stream)
            .toList();

        if (!missingAccepts.isEmpty()) {
          messages.add(new CatchupResponse(nodeIdentifier, replyTo, missingAccepts));
        }

        /// If the other node has seen a higher promise then we must increase our term
        /// to be higher. We do not update our promise as we would be doing that in a learning
        ///  message which is not part of the protocol. Instead, we bump or term. Next time we
        ///  produce an `accept` we will use our term and do a self accept to that which will
        ///  bump pur promise. We do not want to alter the promise when not an `accept` or `prepare` message.
        if (otherHighestPromised.greaterThan(progress.highestPromised())) {
          if (role == TrexRole.LEAD) {
            assert this.term != null;
            this.term = new BallotNumber(
                otherHighestPromised.counter() + 1,
                nodeIdentifier
            );
          }
        }
      }
      case CatchupResponse(_, _, final var catchup) -> {
        // if it there is a gap to the catchup then we will ignore it
        if (!catchup.isEmpty() && catchup.getFirst().logIndex() > progress.highestFixedIndex() + 1) {
          return;
        }

        // Eliminate any breaks. This reduce is by Claud 3.5
        // "Returns the second number (b) if it follows the first (a+1), Otherwise keeps the first number (a)"
        final var highestContiguous = catchup.stream()
            .map(Accept::logIndex)
            .reduce((a, b) -> (a + 1 == b) ? b : a)
            // if we have nothing in the list we return the zero slot which must always be fixed as NOOP
            .orElse(0L);

        final var priorProgress = progress;

        // here we do not check our promise as we trust that the leader knows that the
        // values are fixed so it does not matter if we have a higher promise as it
        // a majority of the nodes have accepted the that message.
        catchup.stream()
            .dropWhile(this::fixedSlot)
            .takeWhile(accept -> accept.logIndex() <= highestContiguous)
            .forEach(accept -> {
              journal.writeAccept(accept);
              progress = progress.withHighestFixed(accept.logIndex());
            fixed(accept, commands);
          });

        if (progress != priorProgress) {
          journal.writeProgress(progress);
        }
      }
    }
  }

  private boolean fixedSlot(Accept accept) {
    return accept.logIndex() <= progress.highestFixedIndex();
  }

  static final String PROTOCOL_VIOLATION_PROMISES = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR Paxos Protocol Violation the promise has been changed when the message is not a PaxosMessage type.";
  static final String PROTOCOL_VIOLATION_NUMBER = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR Paxos Protocol Violation the promise has decreased.";
  static final String PROTOCOL_VIOLATION_INDEX = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR Paxos Protocol Violation the fixed slot index has decreased.";
  static final String PROTOCOL_VIOLATION_SLOT_FIXING = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR Paxos Protocol Violation the promise has been changed when the message is not a SlotFixingMessage type.";
  static final String CRASHED = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR This node has crashed and must be rebooted. The durable journal state is now the only source of truth.";
  static final String CRASHING = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR This node has crashed and must be rebooted. The durable journal state is now the only source of truth: ";

  /// Here we check that we have not violated the Paxos algorithm invariants. If we have then we throw an error.
  /// We also need to check what is described in the wiki page [Cluster Replication With Paxos for the Java Virtual Machine](https://github.com/trex-paxos/trex-paxos-jvm/wiki)
  private void validateProtocolInvariantElseThrowError(TrexMessage input, Progress priorProgress) {
    final var priorPromise = priorProgress.highestPromised();
    final var latestPromise = progress.highestPromised();
    final var protocolMessage = input instanceof PaxosMessage;

    // only prepare and accept messages can change the promise
    if (!priorPromise.equals(latestPromise) && !protocolMessage) {
      logSevereAndThrow(PROTOCOL_VIOLATION_PROMISES, input, priorProgress);
    }

    // promises cannot go backwards the ballot number must only ever increase
    if (latestPromise.lessThan(priorPromise)) {
      logSevereAndThrow(PROTOCOL_VIOLATION_NUMBER, input, priorProgress);
    }

    // the fixed slot index must only ever increase
    if (priorProgress.highestFixedIndex() > progress.highestFixedIndex()) {
      logSevereAndThrow(PROTOCOL_VIOLATION_INDEX, input, priorProgress);
    } else if (priorProgress.highestFixedIndex() != progress.highestFixedIndex()) {
      final var slotFixingMessage = input instanceof SlotFixingMessage;
      if (!slotFixingMessage) {
        logSevereAndThrow(PROTOCOL_VIOLATION_SLOT_FIXING, input, priorProgress);
      }
    }
  }

  private void logSevereAndThrow(String error, TrexMessage input, Progress priorProgress) {
    final var message = error + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
    LOGGER.severe(message);
    throw new AssertionError(message);
  }

  private void abdicate(List<TrexMessage> messages) {
    messages.clear();
    abdicate();
  }

  private void processAcceptResponse(AcceptResponse acceptResponse, Map<Long, AbstractCommand> commands, List<TrexMessage> messages) {
    final var vote = acceptResponse.vote();
    final var logIndex = vote.logIndex();
    Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex)).ifPresent(acceptVotes -> {
      assert acceptVotes.accept.logIndex() == logIndex;
      if (!acceptVotes.chosen()) {
        acceptVotes.responses().put(acceptResponse.from(), acceptResponse);
        Set<AcceptResponse.Vote> vs = acceptVotes.responses().values().stream()
            .map(AcceptResponse::vote).collect(Collectors.toSet());
        final var quorumOutcome =
            quorumStrategy.assessAccepts(logIndex, vs);
        switch (quorumOutcome) {
          case WIN -> {
            LOGGER.log(logAtLevel, () ->
                "WIN logIndex==" + logIndex +
                    " nodeIdentifier==" + nodeIdentifier() +
                    " number==" + acceptVotes.accept().number() +
                    " vs==" + vs);

            acceptVotesByLogIndex.put(logIndex, AcceptVotes.chosen(acceptVotes.accept()));

            // only if we have some contiguous slots that have been accepted we can fix them
            final var fixed = acceptVotesByLogIndex.values().stream()
                .takeWhile(AcceptVotes::chosen)
                .map(AcceptVotes::accept)
                .filter(a -> a.logIndex() > progress.highestFixedIndex())
                .toList();

            if (!fixed.isEmpty()) {
              // run the callback
              for (var slotTerm : fixed) {
                final var accept = journal.readAccept(slotTerm.logIndex()).orElseThrow();
                fixed(accept, commands);
                // free the memory and stop heartbeating out the accepts
                acceptVotesByLogIndex.remove(slotTerm.logIndex());
              }

              // we have fixed slots
              this.progress = progress.withHighestFixed(fixed.getLast().logIndex());
              this.journal.writeProgress(progress);

              // let the cluster know
              messages.add(currentFixedMessage());
            }
          }
          case WAIT -> {
            // do nothing as a quorum has not yet been reached.
          }
          case LOSE ->
            // we are unable to achieve a quorum, so we must back down as to another leader
              abdicate(messages);
        }
      }
    });
  }

  private void fixed(Accept accept, Map<Long, AbstractCommand> commands) {
    final var cmd = accept.command();
    final var logIndex = accept.logIndex();
    LOGGER.log(logAtLevel, () ->
        "FIXED logIndex==" + logIndex +
            " nodeIdentifier==" + nodeIdentifier() +
            " command==" + cmd);
    commands.put(logIndex, cmd);
  }

  void abdicate() {
    this.role = FOLLOW;
    prepareResponsesByLogIndex.clear();
    acceptVotesByLogIndex.clear();
    term = null;
  }

  /**
   * Send a positive vote message to the leader.
   *
   * @param accept The `accept` to positively acknowledge.
   */
  final AcceptResponse ack(Accept accept) {
    return new AcceptResponse(
        nodeIdentifier, accept.number().nodeIdentifier(),
        new AcceptResponse.Vote(nodeIdentifier,
            accept.number().nodeIdentifier(),
            accept.logIndex(), true),
        progress.highestFixedIndex());
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param slotTerm The `accept(S,V,_)` to negatively acknowledge.
   */
  final AcceptResponse nack(Accept.SlotTerm slotTerm) {
    return new AcceptResponse(
        nodeIdentifier,
        slotTerm.number().nodeIdentifier(),
        new AcceptResponse.Vote(nodeIdentifier,
            slotTerm.number().nodeIdentifier(),
            slotTerm.logIndex(),
            false)
        , progress.highestFixedIndex());
  }

  /**
   * Send a positive nextPrepareMessage response message to the leader.
   *
   * @param prepare The nextPrepareMessage message to acknowledge.
   */
  final PrepareResponse ack(Prepare prepare) {
    return new PrepareResponse(
        nodeIdentifier, prepare.number().nodeIdentifier(),
        new PrepareResponse.Vote(nodeIdentifier,
            prepare.number().nodeIdentifier(),
            prepare.logIndex(),
            true,
            prepare.number()),
        journal.readAccept(prepare.logIndex()),
        highestAccepted()
    );
  }

  /**
   * Send a negative nextPrepareMessage response message to the leader.
   *
   * @param prepare The nextPrepareMessage message to reject.
   */
  final PrepareResponse nack(Prepare prepare) {
    return new PrepareResponse(
        nodeIdentifier, prepare.number().nodeIdentifier(),
        new PrepareResponse.Vote(nodeIdentifier,
            prepare.number().nodeIdentifier(),
            prepare.logIndex(),
            false,
            prepare.number()),
        journal.readAccept(prepare.logIndex()), highestAccepted()
    );
  }

  private boolean equalOrHigherAccept(Accept accept) {
    return progress.highestPromised().lessThanOrEqualTo(accept.number());
  }

  private boolean lowerAccept(Accept accept) {
    return accept.number().lessThan(progress.highestPromised());
  }

  private boolean higherAccept(Accept accept) {
    return accept.number().greaterThan(progress.highestPromised());
  }

  public long highestFixed() {
    return progress.highestFixedIndex();
  }

  public byte nodeIdentifier() {
    return nodeIdentifier;
  }

  Optional<Prepare> timeout() {
    if (role == FOLLOW) {
      role = RECOVER;
      term = new BallotNumber(progress.highestPromised().counter() + 1, nodeIdentifier);
      final var prepare = nextPrepareMessage();
      final var selfPrepareResponse = paxos(prepare);
      assert selfPrepareResponse.messages().size() == 1 : "selfPrepare={" + selfPrepareResponse + "}";
      return Optional.of(prepare);
    }
    return Optional.empty();
  }

  public boolean isLeader() {
    return role.equals(LEAD);
  }

  public TrexRole getRole() {
    return role;
  }

  /// The heartbeat method is called by the TrexEngine to send messages to the cluster to stop them
  /// timing out. There may also be dropped messages due to partitions or crashes. So we will also
  /// heartbeat prepare or accept messages that are pending a response.
  public List<TrexMessage> heartbeat() {
    final var result = new ArrayList<TrexMessage>();
    if (isLeader()) {
      result.add(currentFixedMessage());
      result.addAll(pendingAcceptMessages());
    } else if (isRecover()) {
      result.add(currentPrepareMessage());
    }
    return result;
  }

  private List<Accept> pendingAcceptMessages() {
    return LongStream.range(
            progress.highestFixedIndex() + 1,
            journal.highestLogIndex() + 1
        )
        .mapToObj(journal::readAccept)
        .takeWhile(Optional::isPresent)
        .flatMap(Optional::stream)
        .toList();
  }

  Fixed currentFixedMessage() {
    final var highestFixed = highestFixed();
    final var fixedAccept = journal.readAccept(highestFixed).orElseThrow();
    return new Fixed(nodeIdentifier, highestFixed, fixedAccept.number());
  }

  private Prepare currentPrepareMessage() {
    return new Prepare(nodeIdentifier, progress.highestFixedIndex(), term);
  }

  private Prepare nextPrepareMessage() {
    return new Prepare(nodeIdentifier, progress.highestFixedIndex() + 1, term);
  }

  public Accept nextAcceptMessage(Command command) {
    final var a = new Accept(nodeIdentifier, journal.highestLogIndex() + 1, term, command);
    this.acceptVotesByLogIndex.put(a.logIndex(), new AcceptVotes(a));
    return a;
  }

  public boolean isRecover() {
    return role.equals(RECOVER);
  }

  public TrexRole currentRole() {
    return role;
  }

  public long highestAccepted() {
    return journal.highestLogIndex();
  }

  /// This method is for testing purposes only so that we can write unit tests that do not require a TrexEngine.
  /// It is not expected that users of the library will make subclasses of TrexNode in order to use this method.
  @SuppressWarnings("SameParameterValue")
  protected void setRole(TrexRole role) {
    this.role = role;
  }

  private void processPrepareResponse(PrepareResponse prepareResponse, List<TrexMessage> messages) {
    final byte from = prepareResponse.from();
    final long logIndex = prepareResponse.vote().logIndex();
    final var votes = prepareResponsesByLogIndex.computeIfAbsent(logIndex, _ -> new HashMap<>());
    votes.put(from, prepareResponse);
    Set<PrepareResponse.Vote> vs = votes.values().stream()
        .map(PrepareResponse::vote).collect(Collectors.toSet());
    final var quorumOutcome = quorumStrategy.assessPromises(logIndex, vs);
    switch (quorumOutcome) {
      case WAIT ->
        // do nothing as a quorum has not yet been reached.
          prepareResponsesByLogIndex.put(logIndex, votes);
      case LOSE ->
        // we are unable to achieve a quorum, so we must back down
          abdicate(messages);
      case WIN -> {
        // only if we learn that other nodes have prepared higher slots we must nextPrepareMessage them
        votes.values().stream()
            .map(PrepareResponse::highestAcceptedIndex)
            .max(Long::compareTo)
            .ifPresent(higherAcceptedSlot -> {
              final long highestLogIndexProbed = prepareResponsesByLogIndex.lastKey();
              if (higherAcceptedSlot > highestLogIndexProbed) {
                Optional.ofNullable(term).ifPresent(epoch ->
                    LongStream.range(highestLogIndexProbed + 1, higherAcceptedSlot + 1)
                        .forEach(slot -> {
                          prepareResponsesByLogIndex.put(slot, new HashMap<>());
                          messages.add(new Prepare(nodeIdentifier, slot, epoch));
                        }));
              }
            });

        // find the highest accepted command if any
        AbstractCommand highestAcceptedCommand = votes.values().stream()
            .map(PrepareResponse::journaledAccept)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .max(Accept::compareNumbers)
            .map(Accept::command)
            .orElse(NoOperation.NOOP);

        Optional.ofNullable(term).ifPresent(e -> {
          // use the highest accepted command to issue an Accept
          Accept accept = new Accept(nodeIdentifier, logIndex, e, highestAcceptedCommand);
          // issue the accept messages
          messages.add(accept);
          // create the empty map to track the responses
          acceptVotesByLogIndex.put(logIndex, new AcceptVotes(accept));
          // send the Accept to ourselves and process the response
          paxos(paxos(accept).messages().getFirst());
          // we are no longer awaiting the nextPrepareMessage for the current slot
          prepareResponsesByLogIndex.remove(logIndex);
          // if we have had no evidence of higher accepted operationBytes we can promote
          if (prepareResponsesByLogIndex.isEmpty()) {
            role = LEAD;
          }
        });
      }
    }
  }

  /**
   * A record of the votes received by a node from other cluster members.
   */
  // FIXME do not hold the Accept as we need to load it from the journal just hold the number+slot
  public record AcceptVotes(Accept.SlotTerm accept, Map<Byte, AcceptResponse> responses, boolean chosen) {
    public AcceptVotes(Accept accept) {
      this(accept.slotTerm(), new HashMap<>(), false);
    }

    public static AcceptVotes chosen(Accept.SlotTerm accept) {
      return new AcceptVotes(accept, Collections.emptyMap(), true);
    }
  }
}

