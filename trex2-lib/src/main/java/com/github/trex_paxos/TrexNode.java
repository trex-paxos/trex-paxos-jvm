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
import org.jetbrains.annotations.TestOnly;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.github.trex_paxos.TrexNode.TrexRole.*;

/// A TrexNode is a single node in a Paxos cluster. It runs the part-time parliament algorithm. It requires
/// the following collaborating classes:
///
/// * One [Journal] which must be crash durable storage.
/// * One [QuorumStrategy] which may be a simple majority, in the future FPaxos or UPaxos.
///
/// This class logs to JUL logging as severe. You can configure JUL logging to
/// bridge to your chosen logging framework. This class is not thread safe. The [TrexEngine] will wrap this class and
/// use a virtual thread friendly mutex to ensure that only one thread is calling the algorithm method at a time.
///
/// The wrapping [TrexEngine] will call {@link Journal#sync()} unless it has be constructed with `hostManagedTransactions=true`.
///
/// This class will mark itself as crashed if it has exceptions due to journal IO errors or if it reads corrupt data.
/// After it has logged to JUL and stderr it will throw the original Exception if any. After that it will always throw an
/// AssertionError see {@link #isCrashed()}.
public class TrexNode {
  ///  We are using JUL logging to reduce dependencies. You can configure JUL logging to bridge to your chosen logging framework.
  static final Logger LOGGER = Logger.getLogger("");

  /// We log when we win
  private final Level logAtLevel;

  /// Is {@link #isCrashed()}
  volatile private boolean crashed = false;

  /// Is {@link TrexEngine#isClosed()}
  volatile private boolean closed = false;

  /// A node is marked as crashed if:
  ///
  /// 1. The journal experiences an exception writing to the journal.
  /// 2. Data is read from the journal that violates the protocol invariants.
  /// 3. Explicitly checks of the protocol invariants fail.
  ///
  /// If you set `hostManagedTransactions=true` then you must catch all journal exceptions and call {@link TrexEngine#crash()}.
  ///
  /// It is expected that the operator must reboot the node to get back into a clean state. If the journal is corrupt then the
  /// operator must restore the journal from a backup and allow it to catch up else clone another node and
  /// change the `nodeIdentifier` in the cloned journal to match the correct nodeIdentifier.
  ///
  /// You might use a kubernetes health check or similar to monitor this method and have it automatically restart the
  /// processes if it becomes crashed.
  @SuppressWarnings("unused")
  public boolean isCrashed() {
    return crashed;
  }

  /// Create a new TrexNode that will load the current progress from the journal. The journal must have been pre-initialised.
  ///
  /// @param logAtLevel     The level to log when values are known to be chosen which is logged as "WIN" and when are know to be sequentially fixed with is logged as "FIXED".
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

  /// The current node identifier. This must be globally unique in the cluster.
  final byte nodeIdentifier;

  /// The durable storage and durable log.
  final Journal journal;

  /// The quorum strategy that may be trivial or may be cluster membership aware to implement UPaxos. You can manage that using Paxos itself.
  final QuorumStrategy quorumStrategy;

  /// If we have rebooted then we start off as a follower.
  /// This is only package private to allow unit tests to set the role.
  TrexRole role = FOLLOW;

  /// The initial progress is loaded from the Journal at startup.
  Progress progress;

  /// During a recovery we will track all the slots that we are probing to find the highest accepted operationBytes.
  final NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  /// When leading we will track the responses to a stream of accept messages.
  final NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /// The term of a node is the value that it will use with either the next `prepare` or `accept` message.
  /// It is only used by the leader and recoverer. It will be null for a follower.
  BallotNumber term = null;

  /// This method wraps the main algorithm method with guards to ensure safety. The node will mark itself as crashed
  /// if the main algorithm threw an error trying to use the journal else was given corrupted data. It will also mark
  /// itself as crashed if it detects the protocol invariants have been violated. See {@link #isCrashed()} which can
  /// be monitored by something like a kubernetes health checks to restart the
  /// node automatically if it is crashed. See {@link #algorithm(TrexMessage, List, TreeMap)} for the main logic.
  /// this method is not thread safe. When this method returns the journal must
  /// be made crash durable before sending out any messages.The [TrexEngine] will wrap this class and use a virtual thread friendly mutex to
  /// and flush the journal if it is not using host managed transactions. This method will throw an IllegalStateException
  /// if the node is crashed for all future calls. The operator must reboot the node. If the journal is corrupt then the
  /// operator must restore the journal from a backup possibly or clone another node by change the `nodeIdentifier` in the
  /// [Journal].
  ///
  /// @param input The message to process.
  /// @return A possibly empty list of messages to send out to the cluster plus a possibly empty list of chosen commands to up-call to the host
  /// application. The journal state must be made crash durable before sending out any messages.
  /// @throws IllegalStateException If the node has been marked as crashed it will always throw an exception and will
  /// need rebooting. See {@link #isCrashed()}.
  TrexResult paxos(TrexMessage input) {
    if (crashed) {
      /// We are in an undefined or corrupted state. See {@link #isCrashed()}
      LOGGER.severe(CRASHED);
      /// Just in case the host application has not setup JUL logging property we log to stderr as a last resort.
      System.err.println(CRASHED);
      throw new IllegalStateException(CRASHED);
    }
    /// This will hold any outbound message that must only be sent after the journal has been flushed to durable storage.
    List<TrexMessage> messages = new ArrayList<>();
    /// This will hold any fixed commands. These may be written to the data store under the same translation as the journal.stat.
    TreeMap<Long, AbstractCommand> commands = new TreeMap<>();
    /// This tracks what our old state was so that we can crash if we change the state for the wrong message types.
    final var priorProgress = progress;
    try {
      /// Run the actual algorithm. This method is void as we the command and message are out parameters.
      algorithm(input, messages, commands);
    } catch (Throwable e) {
      /// The most probable reason to throw is an IOError from the journal else it returned corrupt data we cannot process. .
      crashed = true;
      /// Log that we are crashing and log the reason.
      LOGGER.log(Level.SEVERE, CRASHING + e, e);
      /// In case the application developer has not correctly configured logging JUL logging we log to stderr.
      System.err.println(CRASHING + e);
      //noinspection CallToPrintStackTrace
      e.printStackTrace();
      /// We throw yet the finally block will also run and may also log errors about invariants being violated before
      /// the thrown issue is sent up to the host application.
      throw e;
    } finally {
      if (!crashed) {
        /// Here we always check the invariants in finally block see {@link #isCrashed()}
        if (priorProgress != progress && !priorProgress.equals(progress)) {
          // The general advice is not to throw. In this case the general advice is wrong.
          // We must throw as we have violated the protocol and that should be seen as fatal.
          validateProtocolInvariants(input, priorProgress);
        }
        if (!commands.isEmpty()) {
          // The general advice is not to throw. In this case the general advice is wrong.
          // We must throw if the journal gives us weird commands as that is a fatal error.
          validateCommandIndexes(input, commands, priorProgress);
        }
      }
    }
    return new TrexResult(messages, commands);
  }

  /// This is the main Paxos Algorithm. It is not public as it is wrapped in guards that check the invariants and
  /// ensure that the node is stopped if it is in unknown state of if the invariants were violated.
  ///
  /// @param input    The message to process.
  /// @param messages This is an out argument that gathers the list of messages to send out to the cluster.
  /// @param commands This is an out argument of chosen command values by slot to up-call to the host application.
  private void algorithm(TrexMessage input,
                         List<TrexMessage> messages,
                         TreeMap<Long, AbstractCommand> commands) {
    if (closed) {
      LOGGER.warning("This node has been closed so has shutdown and will not process any more messages.");
      return;
    }
    switch (input) {
      case Accept accept -> {
        final var number = accept.slotTerm().number();
        final var logIndex = accept.slotTerm().logIndex();
        if (lowerAccept(accept) || fixedSlot(accept.slot())) {
          messages.add(nack(accept.slotTerm()));
          // if the other node is behind tell them that the slot is fixed. this will force them to catchup.
          sendFixedToBehindNode(accept.slot(), messages);
        } else if (equalOrHigherAccept(accept)) {
          // always journal first
          journal.writeAccept(accept);
          if (higherAccept(accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            this.progress = progress.withHighestPromised(number);
            // we must change our own vote if we are an old leader
            if (this.role == LEAD) {
              // does this change our prior self vote?
              Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex))
                  .ifPresent(acceptVotes -> {
                    final var oldNumber = acceptVotes.accept().number();
                    if (oldNumber.lessThan(number)) {
                      // we have accepted a higher accept which is a promise as per https://stackoverflow.com/a/29929052
                      acceptVotes.responses().put(nodeIdentifier(), nack(acceptVotes.accept()));
                      Set<AcceptResponse.Vote> vs = acceptVotes.responses().values().stream()
                          .map(AcceptResponse::vote).collect(Collectors.toSet());
                      final var quorumOutcome =
                          quorumStrategy.assessAccepts(logIndex, vs);
                      if (quorumOutcome == QuorumStrategy.QuorumOutcome.LOSE) {
                        // this happens in a three node cluster when an isolated split brain leader rejoins
                        abdicate(messages);
                      }
                    }
                  });
            }
          }
          journal.writeProgress(this.progress);
          final var ack = ack(accept);
          if (number.nodeIdentifier() == nodeIdentifier) {
            // we vote for ourself
            paxos(ack);
          }
          messages.add(ack);
        } else {
          assert false;
        }
      }
      case Prepare prepare -> {
        final var number = prepare.slotTerm().number();
        if (number.lessThan(progress.highestPromised()) || fixedSlot(prepare.slot())) {
          // nack a low nextPrepareMessage else any nextPrepareMessage for a fixed slot sending any accepts they are missing
          messages.add(nack(prepare));
          // if the other node is behind tell them that the slot is fixed. this will force them to catchup.
          sendFixedToBehindNode(prepare.slot(), messages);
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher nextPrepareMessage
          final var newProgress = progress.withHighestPromised(number);
          journal.writeProgress(newProgress);
          final var ack = ack(prepare);
          messages.add(ack);
          this.progress = newProgress;
          // leader or recoverer should give way to a higher nextPrepareMessage
          if (number.nodeIdentifier() != nodeIdentifier && role != FOLLOW) {
            abdicate(messages);
          }
          // we vote for ourself
          if (newProgress.nodeIdentifier() == nodeIdentifier) {
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
      case Fixed(final var fixedFrom, SlotTerm(final var fixedSlot, final var fixedNumber)) -> {
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
              // the leader is the distinguished learner that recurses so this is positive confirmation of another live leader.
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
        /// message which is not part of the protocol. Instead, we bump or term. Next time we
        /// produce an `accept` we will use our term and do a self accept to that which will
        /// bump pur promise. We do not want to alter the promise when not an `accept` or `prepare` message.
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
        if (!catchup.isEmpty() && catchup.getFirst().slot() > progress.highestFixedIndex() + 1) {
          return;
        }

        // Eliminate any breaks. This reduce is by Claud 3.5
        // "Returns the second number (b) if it follows the first (a+1), Otherwise keeps the first number (a)"
        final var highestContiguous = catchup.stream()
            .map(Accept::slot)
            .reduce((a, b) -> (a + 1 == b) ? b : a)
            // if we have nothing in the list we return the zero slot which must always be fixed as NOOP
            .orElse(0L);

        final var priorProgress = progress;

        // here we do not check our promise as we trust that the leader knows that the
        // values are fixed so it does not matter if we have a higher promise as it
        // a majority of the nodes have accepted the that message.
        catchup.stream()
            .dropWhile(a->fixedSlot(a.slot()))
            .takeWhile(accept -> accept.slot() <= highestContiguous)
            .forEach(accept -> {
              journal.writeAccept(accept);
              progress = progress.withHighestFixed(accept.slot());
              fixed(accept, commands);
            });

        if (progress != priorProgress) {
          journal.writeProgress(progress);
        }
      }
    }
  }

  // TODO write two tests that a node behind a partition catches up from other follower node or a leader
  private void sendFixedToBehindNode(Long otherSlot, List<TrexMessage> messages) {
    if (behind(otherSlot)) {
      journal.readAccept(progress.highestFixedIndex())
          .ifPresent(fixedAccept ->
              messages.add(new Fixed(nodeIdentifier, fixedAccept.slot(), fixedAccept.number())));
    }
  }

  private boolean behind(long slot) {
    return slot < progress.highestFixedIndex();
  }

  private boolean fixedSlot(long slot) {
    return slot <= progress.highestFixedIndex();
  }

  static final String PROTOCOL_VIOLATION_PROMISES = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR CRASHED Paxos Protocol Violation the promise has been changed when the message is not a PaxosMessage type.";
  static final String PROTOCOL_VIOLATION_NUMBER = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR CRASHED  Paxos Protocol Violation the promise has decreased.";
  static final String PROTOCOL_VIOLATION_INDEX = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR CRASHED  Paxos Protocol Violation the fixed slot index has decreased.";
  static final String PROTOCOL_VIOLATION_SLOT_FIXING = TrexNode.class.getCanonicalName() + " FATAL SEVERE ERROR CRASHED  Paxos Protocol Violation the promise has been changed when the message is not a LearningMessage type.";
  static final String CRASHED = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR  CRASHED This node has crashed and must be rebooted. The durable journal state (if not corrupted) is now the only source of truth.";
  static final String CRASHING = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR  CRASHED This node has crashed and must be rebooted. The durable journal state (if not corrupted) is now the only source of truth to to throwable: ";
  static final String COMMAND_INDEXES = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR CRASHED This node has issued commands that do not align to its committed slot index: ";
  static final String COMMAND_GAPS = TrexNode.class.getCanonicalName() + "FATAL SEVERE ERROR CRASHED This node has issued commands that are not sequential in commited slot index: ";

  /// Here we check that we have not violated the Paxos algorithm invariants. If we have then we lock then mark the node as crashed.
  private void validateProtocolInvariants(TrexMessage input, Progress priorProgress) {
    final var priorPromise = priorProgress.highestPromised();
    final var latestPromise = progress.highestPromised();
    final var protocolMessage = input instanceof PaxosMessage;

    // only prepare and accept messages can change the promise
    if (!priorPromise.equals(latestPromise) && !protocolMessage) {
      this.crashed = true;
      final var message = PROTOCOL_VIOLATION_PROMISES + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
      LOGGER.severe(message);
    }

    // promises cannot go backwards the ballot number must only ever increase
    if (latestPromise.lessThan(priorPromise)) {
      this.crashed = true;
      final var message = PROTOCOL_VIOLATION_NUMBER + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
      LOGGER.severe(message);
    }

    // the fixed slot index must only ever increase
    if (priorProgress.highestFixedIndex() > progress.highestFixedIndex()) {
      this.crashed = true;
      final var message = PROTOCOL_VIOLATION_INDEX + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
      LOGGER.severe(message);
    } else if (priorProgress.highestFixedIndex() != progress.highestFixedIndex()) {
      final var slotFixingMessage = input instanceof LearningMessage;
      if (!slotFixingMessage) {
        this.crashed = true;
        final var message = PROTOCOL_VIOLATION_SLOT_FIXING + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
        LOGGER.severe(message);
      }
    }
  }

  /// Here we check that we have not violated the Paxos algorithm invariants. If we have then we lock then mark the node as crashed.
  private void validateCommandIndexes(TrexMessage input, TreeMap<Long, AbstractCommand> commands, Progress priorProgress) {

    if (commands.lastKey() != progress.highestFixedIndex()) {
      crashed = true;
      final var message = COMMAND_INDEXES + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
      LOGGER.severe(message);
    }
    // "Returns the second number (b) if it follows the first (a+1), Otherwise keeps the first number (a)"
    final var highestContiguous = commands.keySet().stream()
        .reduce((a, b) -> (a + 1 == b) ? b : a)
        // if we have nothing in the list we return the zero slot which must always be fixed as NOOP
        .orElse(0L);

    if (highestContiguous != progress.highestFixedIndex()) {
      crashed = true;
      final var message = COMMAND_GAPS + " input=" + input + " priorProgress=" + priorProgress + " progress=" + progress;
      LOGGER.severe(message);
    }
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
    final var logIndex = accept.slot();
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
            accept.slot(), true),
        progress.highestFixedIndex());
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param slotTerm The `accept(S,V,_)` to negatively acknowledge.
   */
  final AcceptResponse nack(SlotTerm slotTerm) {
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
            prepare.slot(),
            true,
            prepare.number()),
        journal.readAccept(prepare.slot()),
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
            prepare.slot(),
            false,
            prepare.number()),
        journal.readAccept(prepare.slot()), highestAccepted()
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
  ///
  /// @return A list of messages to send to the cluster. The list is empty if the node is a follower.
  public List<TrexMessage> createHeartbeatMessages() {
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
    this.acceptVotesByLogIndex.put(a.slot(), new AcceptVotes(a.slotTerm()));
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
        // This node cannot leader as it never made a promise high enough that increment the counter
        // can let it lead. Once it has made a promise to the new leader it will be up to date and
        // at the next timeout it will increment the counter and be able to lead.
          abdicate(messages);
      case WIN -> {
        // only if we learn that other nodes have prepared higher slots we must issue send message for them
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
          acceptVotesByLogIndex.put(logIndex, new AcceptVotes(accept.slotTerm()));
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

  public int clusterSize() {
    return quorumStrategy.clusterSize();
  }

  /// This node must no longer run as it is an unknown state due to an exception. You must set this to step any more
  /// commands getting picked and sent to clients. If you are running host managed transactions then you should call
  /// this if you ever get any exceptions you do not recover your state from the data store. `TrexEngine` will call this
  /// when it has a thread interrupted which is assumes is due to the whole application shutting down.
  public void crash() {
    LOGGER.severe("We are marked as crashed which is fine during a shutdown of the cluster but a serious issue if not expected.");
    this.crashed = true;
  }

  public void close() {
    this.closed = true;
  }

  public boolean isClosed() {
    return closed;
  }

  @TestOnly
  public Progress progress() {
    return progress;
  }

  /**
   * A record of the votes received by a node from other cluster members.
   */
  public record AcceptVotes(SlotTerm accept, Map<Byte, AcceptResponse> responses, boolean chosen) {
    public AcceptVotes(SlotTerm slotTerm) {
      this(slotTerm, new HashMap<>(), false);
    }

    public static AcceptVotes chosen(SlotTerm slotTerm) {
      return new AcceptVotes(slotTerm, Collections.emptyMap(), true);
    }
  }

  /// The roles used by nodes in the paxos algorithm. The paper Paxos Made Simple by Leslie Lamport very clearly states:
  ///
  /// > A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm
  ///
  /// This means we have a leader. We also have followers who have not yet timed-out on the leader. Finally, we have the
  /// recover role which is a node that is sending out prepare messages in an attempt to fix the values sent by a prior leader.
  public enum TrexRole {
    /// A follower is a node that is not currently leading the paxos algorithm. We may time out on a follower and attempt to become a leader.
    FOLLOW,
    /// If we are wanting to lead we first run rounds of paxos over all known slots to fix the values sent by any prior leader.
    RECOVER,
    /// Only after we have recovered all slots known to have been sent any values by the prior leader will we become a leader
    /// who no longer needs to recover any slots.
    LEAD
  }
}

