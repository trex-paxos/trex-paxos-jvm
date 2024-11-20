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
/// collaborating classes
/// - A [Journal] which must be crash durable storage. The wrapping [TrexEngine] must flush the journal to durable state (fsync) before sending out any messages.
/// - A [QuorumStrategy] which may be a simple majority, in the future FPaxos or UPaxos.
public class TrexNode {
  static final Logger LOGGER = Logger.getLogger("");

  private final Level logAtLevel;

  /// Create a new TrexNode that will load the current progress from the journal. The journal must have been pre-initialised.
  ///
  /// @param nodeIdentifier The unique node identifier. This must be unique across the cluster and across enough time for prior messages to have been forgotten.
  /// @param quorumStrategy The quorum strategy that may be a simple majority, else things like FPaxos or UPaxos
  /// @param journal        The durable storage and durable log. This must be pre-initialised.
  public TrexNode(Level logAtLevel, byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.progress = journal.loadProgress(nodeIdentifier);
    this.logAtLevel = logAtLevel;
  }

  /// The current node identifier. This must be globally unique in the cluster. You can manage that using Paxos itself.
  final byte nodeIdentifier;

  /// The durable storage and durable log.
  final Journal journal;

  // The quorum strategy that may be trivial or may be cluster membership aware to implement UPaxos. You can manage that using Paxos itself.
  final QuorumStrategy quorumStrategy;

  /// If we have rebooted then we start off as a follower.
  private TrexRole role = FOLLOW;

  /// The initial progress is loaded from the Journal at startup. It is the last known state of the node prior to a crash.
  Progress progress;

  /// During a recovery we will track all the slots that we are probing to find the highest accepted operationBytes.
  final NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  // When leading we will track the responses to a stream of accept messages.
  final NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /// Lamport's "Paxos Made Simple" uses the terminology 'proposal number'. Here we use the term 'BallotNumber' as it
  /// is better aligned to his original The Part-time Parliament paper. We use the term 'BallotNumber' as it is more
  /// aligned to how we count votes from the other nodes.
  BallotNumber term = null;

  /// This is the main Paxos Algorithm. It is not public as a TrexEngine will wrap this to handle specifics of resetting
  /// timeouts. This method will recurse without returning when we need tpo send a message to ourselves. As a side
  /// effect the progress record will be updated and accept messages will be added into the journal.
  ///
  /// VERY IMPORTANT: The journal *must* be flushed to durable storage before sending out any messages returned from
  /// this method. That ultimately inhibits throughput but cannot be skipped without breaking the algorithm.
  ///
  /// @param input The message to process.
  /// @return A possibly empty list of messages to send out to the cluster plus a possibly empty list of chosen commands to up-call to the host
  /// application.
  TrexResult paxos(TrexMessage input) {
    List<TrexMessage> messages = new ArrayList<>();
    Map<Long, AbstractCommand> commands = new TreeMap<>();
    switch (input) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
          messages.add(nack(accept));
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);
          // record that we have accepted a higher slot
          if (accept.logIndex() > progress.highestAcceptedIndex()) {
            progress = progress.withHighestAccepted(accept.logIndex());
          }
          if (higherAccept(progress, accept)) {
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
                      // we have accepted a higher accept which is a promise as per http://stackoverflow.com/q/29880949/329496
                      acceptVotes.responses().put(nodeIdentifier(), nack(acceptVotes.accept()));
                      Set<Vote> vs = acceptVotes.responses().values().stream()
                          .map(AcceptResponse::vote).collect(Collectors.toSet());
                      final var quorumOutcome =
                          quorumStrategy.assessAccepts(logIndex, vs);
                      if (quorumOutcome == QuorumOutcome.LOSE) {
                        // this happens in a three node cluster when an isolated split brain leader rejoins
                        backdown();
                      }
                    }
                  });
            }
          }
          journal.saveProgress(this.progress);
          final var ack = ack(accept);
          if (accept.number().nodeIdentifier() == nodeIdentifier) {
            // we vote for ourself
            paxos(ack);
          }
          messages.add(ack);
        } else {
          throw new AssertionError("unreachable progress={" + progress + "}, accept={" + accept + "}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.number();
        if (number.lessThan(progress.highestPromised()) || prepare.logIndex() <= progress.highestCommittedIndex()) {
          // nack a low nextPrepareMessage else any nextPrepareMessage for a committed slot sending any accepts they are missing
          messages.add(nack(prepare));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher nextPrepareMessage
          final var newProgress = progress.withHighestPromised(prepare.number());
          journal.saveProgress(newProgress);
          final var ack = ack(prepare);
          messages.add(ack);
          this.progress = newProgress;
          // leader or recoverer should give way to a higher nextPrepareMessage
          if (prepare.number().nodeIdentifier() != nodeIdentifier && role != FOLLOW) {
            backdown();
          }
          // we vote for ourself
          if (prepare.number().nodeIdentifier() == nodeIdentifier) {
            paxos(ack);
          }
        } else if (number.equals(progress.highestPromised())) {
          messages.add(ack(prepare));
        } else {
          throw new AssertionError("unreachable progress={" + progress + "}, nextPrepareMessage={" + prepare + "}");
        }
      }
      case AcceptResponse(final var vote, final var otherProgress) -> {
        // FIXME reorder this to be more readable
        // Both Leader and Recoverer can receive AcceptResponses
        if (FOLLOW != role && vote.to() == nodeIdentifier) {
          // An isolated leader rejoining must back down
          if (LEAD == role && otherProgress.highestCommittedIndex() > progress.highestCommittedIndex()) {
            backdown();
          } else {
            final var logIndex = vote.logIndex();
            Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex)).ifPresent(acceptVotes -> {
              if (!acceptVotes.chosen()) {
                acceptVotes.responses().put(vote.from(), (AcceptResponse) input);
                Set<Vote> vs = acceptVotes.responses().values().stream()
                    .map(AcceptResponse::vote).collect(Collectors.toSet());
                final var quorumOutcome =
                    quorumStrategy.assessAccepts(logIndex, vs);
                switch (quorumOutcome) {
                  case WIN -> {
                    LOGGER.log(logAtLevel, () ->
                        "WIN logIndex==" + logIndex +
                            " nodeIdentifier==" + nodeIdentifier() +
                            " number==" + acceptVotes.accept().number() +
                            " value==" + acceptVotes.accept().command() +
                            " vs==" + vs);

                    // we have a quorum at that log index but due to lost messages we may have gaps before it.
                    acceptVotesByLogIndex.put(logIndex, AcceptVotes.chosen(acceptVotes.accept()));

                    final var committed = LongStream.rangeClosed(progress.highestCommittedIndex() + 1, logIndex)
                        .mapToObj(i -> Optional.ofNullable(acceptVotesByLogIndex.get(i)))
                        .takeWhile(Optional::isPresent)
                        .flatMap(Optional::stream)
                        .map(AcceptVotes::accept)
                        .filter(accept -> accept.number().equals(vote.number()))
                        .toList();

                    // only if we have some contiguous slots that we can commit which might still not be all slots
                    if (!committed.isEmpty()) {
                      // run the callback
                      for (var accept : committed) {
                        commit(accept, commands);
                        // free the memory and stop heartbeating out the accepts
                        acceptVotesByLogIndex.remove(accept.logIndex());
                      }

                      // we have committed
                      this.progress = progress.withHighestCommitted(committed.getLast().logIndex());

                      // let the cluster know
                      messages.add(currentCommitMessage());
                    }
                  }
                  case WAIT -> {
                    // do nothing as a quorum has not yet been reached.
                  }
                  case LOSE ->
                    // we are unable to achieve a quorum, so we must back down as to another leader
                      backdown();
                }
              }
            });
          }
        }
      }
      case PrepareResponse prepareResponse -> {
        if (RECOVER == role) {
          if (prepareResponse.vote().to() == nodeIdentifier) {
            final byte from = prepareResponse.from();
            final long logIndex = prepareResponse.vote().logIndex();

            final var votes = prepareResponsesByLogIndex.computeIfAbsent(logIndex, _ -> new HashMap<>());
            votes.put(from, prepareResponse);
            Set<Vote> vs = votes.values().stream()
                .map(PrepareResponse::vote).collect(Collectors.toSet());
            final var quorumOutcome = quorumStrategy.assessPromises(logIndex, vs);
            switch (quorumOutcome) {
              case WAIT ->
                // do nothing as a quorum has not yet been reached.
                  prepareResponsesByLogIndex.put(logIndex, votes);
              case LOSE ->
                // we are unable to achieve a quorum, so we must back down
                  backdown();
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
                    .map(PrepareResponse::highestUncommitted)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .max(Accept::compareTo)
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
                    this.role = LEAD;
                  }
                });
              }
            }
          }
        }
      }
      case Commit(
          final var commitFrom,
          final var commitNumber,
          final var commitSlot
      ) -> {
        // FIXME it should be possible to do some deduction here to avoid the catchup
        if (commitSlot == highestCommitted() + 1) {
          // we must have the correct number at the slot
          final var commitableAccept = journal.loadAccept(commitSlot)
                  .filter(accept -> accept.number().equals(commitNumber))
                  .orElse(null);

          // make the callback to the main application
          Optional.ofNullable(commitableAccept).ifPresent(accept -> {
            commit(accept, commands);
            progress = progress.withHighestCommitted(commitSlot);
            journal.saveProgress(progress);
            if (!role.equals(FOLLOW)) {
              backdown();
            }
          });
        }

        final var highestCommittedIndex = progress.highestCommittedIndex();

        if (commitSlot > highestCommittedIndex) {
          final var slotGaps = LongStream.range(progress.highestCommittedIndex(), commitSlot + 1)
            .toArray();
          if (slotGaps.length > 0) {
            messages.add(new Catchup(nodeIdentifier, commitFrom, slotGaps, progress.highestCommittedIndex()));
          }
        }
      }
      case Catchup(final byte replyTo, _, final long[] _, final var highestCommittedIndex) -> {
        final var currentCommitMessage = currentCommitMessage();

        // load the slows they do not know that they are missing
        final var missingAccepts = LongStream.rangeClosed(highestCommittedIndex + 1, progress.highestCommittedIndex())
            .mapToObj(journal::loadAccept)
            .flatMap(Optional::stream)
            .toList();

        messages.add(new CatchupResponse(nodeIdentifier, replyTo, missingAccepts, currentCommitMessage));
      }
      case CatchupResponse cr -> {
        // drop anything we have already committed
        cr.catchup().stream()
            .dropWhile(accept -> accept.logIndex() <= progress.highestCommittedIndex())
            .forEach(accept -> {
              journal.journalAccept(accept);
              commit(accept, commands);
            });

        progress = progress.withHighestCommitted(cr.commit().committedLogIndex());
        journal.saveProgress(progress);
      }
    }
    return new TrexResult(messages, commands);
  }

  private void commit(Accept accept, Map<Long, AbstractCommand> commands) {
    final var cmd = accept.command();
    final var logIndex = accept.logIndex();
    LOGGER.log(logAtLevel, () ->
        "COMMIT logIndex==" + logIndex +
            " nodeIdentifier==" + nodeIdentifier() +
            " command==" + cmd);
    commands.put(logIndex, cmd);
  }

  void backdown() {
    this.role = FOLLOW;
    prepareResponsesByLogIndex.clear();
    acceptVotesByLogIndex.clear();
    term = null;
  }

  /**
   * Send a positive vote message to the leader.
   *
   * @param accept The accept message to acknowledge.
   */
  AcceptResponse ack(Accept accept) {
    final var vote = new Vote(
        nodeIdentifier,
        accept.number().nodeIdentifier(),
        accept.logIndex(),
        true,
        accept.number());
    return
        new AcceptResponse(
            vote
            , progress);
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param accept The accept message to reject.
   */
  AcceptResponse nack(Accept accept) {
    return new AcceptResponse(
        new Vote(nodeIdentifier, accept.number().nodeIdentifier(), accept.logIndex(), false, accept.number())
        , progress);
  }

  /**
   * Send a positive nextPrepareMessage response message to the leader.
   *
   * @param prepare The nextPrepareMessage message to acknowledge.
   */
  PrepareResponse ack(Prepare prepare) {
    return new PrepareResponse(
        new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), true, prepare.number()),
        highestAccepted(), journal.loadAccept(prepare.logIndex())
    );
  }

  /**
   * Send a negative nextPrepareMessage response message to the leader.
   *
   * @param prepare The nextPrepareMessage message to reject.
   */
  PrepareResponse nack(Prepare prepare) {
    return new PrepareResponse(
        new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), false, prepare.number()),
        highestAccepted(), journal.loadAccept(prepare.logIndex())
    );
  }

  static boolean equalOrHigherAccept(Progress progress, Accept accept) {
    return progress.highestPromised().lessThanOrEqualTo(accept.number());
  }

  static boolean higherAcceptForCommittedSlot(Accept accept, Progress progress) {
    return accept.number().greaterThan(progress.highestPromised()) &&
        accept.logIndex() <= progress.highestCommittedIndex();
  }

  static Boolean lowerAccept(Progress progress, Accept accept) {
    return accept.number().lessThan(progress.highestPromised());
  }

  static Boolean higherAccept(Progress progress, Accept accept) {
    return accept.number().greaterThan(progress.highestPromised());
  }

  public long highestCommitted() {
    return progress.highestCommittedIndex();
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
      result.add(currentCommitMessage());
      result.addAll(pendingAcceptMessages());
    } else if (isRecover()) {
      result.add(currentPrepareMessage());
    }
    return result;
  }

  private List<Accept> pendingAcceptMessages() {
    return LongStream.range(
            progress.highestCommittedIndex() + 1,
            progress.highestAcceptedIndex() + 1
        )
        .mapToObj(journal::loadAccept)
        .takeWhile(Optional::isPresent)
        .flatMap(Optional::stream)
        .toList();
  }

  Commit currentCommitMessage() {
    final var highestCommitted = highestCommitted();
    final var commitedAccept = journal.loadAccept(highestCommitted).orElseThrow();
    return new Commit(nodeIdentifier, commitedAccept.number(), highestCommitted);
  }

  private Prepare currentPrepareMessage() {
    return new Prepare(nodeIdentifier, progress.highestCommittedIndex(), term);
  }

  private Prepare nextPrepareMessage() {
    return new Prepare(nodeIdentifier, progress.highestCommittedIndex() + 1, term);
  }

  public Accept nextAcceptMessage(Command command) {
    final var a = new Accept(nodeIdentifier, progress.highestAcceptedIndex() + 1, progress.highestPromised(), command);
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
    return progress.highestAcceptedIndex();
  }
}
