package com.github.trex_paxos;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.github.trex_paxos.TrexRole.*;

public class TrexNode {
  /**
   * The current node identifier. This must be globally unique.
   */
  final byte nodeIdentifier;

  /**
   * The durable storage and durable log.
   */
  final Journal journal;

  /**
   * The quorum strategy that may be a simple majority, or FPaxos or UPaxos
   */
  final QuorumStrategy quorumStrategy;

  /**
   * If we have rebooted then we are a follower.
   */
  private TrexRole role = FOLLOW;

  @SuppressWarnings("unused")
  public TrexRole currentRole() {
    return role;
  }

  /**
   * The initial progress must be loaded from the journal. TA fresh node the journal must be pre-initialised.
   */
  Progress progress;

  /**
   * During a recovery we will track all the slots that we are probing to find the highest accepted values.
   */
  NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  /**
   * When leading we will track the responses to a stream of accept messages.
   */
  NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /**
   * The host application will need to learn that a log index has been chosen.
   */
  final UpCall upCall;

  /**
   * When we are leader we need to now the highest ballot number to use.
   */
  Optional<BallotNumber> term = Optional.empty(); // FIXME this should be renamed to Term

  public TrexNode(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal, UpCall upCall) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.upCall = upCall;
    this.progress = journal.loadProgress(nodeIdentifier);
  }

  private boolean invariants() {
    return switch (role) {
      case FOLLOW -> // follower is tracking no state and has no epoch
          term.isEmpty() && prepareResponsesByLogIndex.isEmpty() && acceptVotesByLogIndex.isEmpty();
      case RECOVER -> // candidate has an epoch and is tracking some prepare responses and/or some accept votes
          term.isPresent() && (!prepareResponsesByLogIndex.isEmpty() || !acceptVotesByLogIndex.isEmpty());
      case LEAD -> // leader has an epoch and is tracking no prepare responses
          term.isPresent() && prepareResponsesByLogIndex.isEmpty();
    };
  }

  /**
   * The main entry point for the Trex paxos algorithm.
   *
   * @param input   The message to process.
   * @param network The network to send messages out via.
   * @param members The set of all members in the cluster.
   * @throws AssertionError If the algorithm is in an invalid state.
   */
  public void paxos(TrexMessage input, Network network, Set<Byte> members) {
    // when testing we can check invariants
    assert invariants() : STR."invariants failed: \{this.role} \{this.term} \{this.prepareResponsesByLogIndex} \{this.acceptVotesByLogIndex}";

    switch (input) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
          network.send(accept.from(), nack(accept));
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);
          if (higherAccept(progress, accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            Progress updatedProgress = progress.withHighestPromised(accept.number());
            journal.saveProgress(updatedProgress);
            this.progress = updatedProgress;
          }
          network.send(accept.from(), accept);
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, accept=\{accept}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.number();
        if (number.lessThan(progress.highestPromised()) || prepare.logIndex() <= progress.highestCommitted()) {
          // nack a low prepare else any prepare for a committed slot sending any accepts they are missing
          network.send(prepare.from(), nack(prepare, loadCatchup(prepare.logIndex())));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher prepare
          final var newProgress = progress.withHighestPromised(prepare.number());
          journal.saveProgress(newProgress);
          network.send(prepare.from(), ack(prepare));
          this.progress = newProgress;
          // leader or recoverer should give way to a higher prepare
          if (this.role != FOLLOW)
            backdown();
        } else if (number.equals(progress.highestPromised())) {
          network.send(prepare.from(), ack(prepare));
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, prepare=\{prepare}");
        }
      }
      case AcceptResponse acceptResponse -> {
        if (FOLLOW != role && acceptResponse.vote().to() == nodeIdentifier) {
          // Both Leader and Recoverer can receive AcceptResponses
          final var logIndex = acceptResponse.vote().logIndex();
          Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex)).ifPresent(acceptVotes -> {
            if (!acceptVotes.chosen()) {
              acceptVotes.responses().put(acceptResponse.from(), acceptResponse);
              Set<Vote> vs = acceptVotes.responses().values().stream()
                  .map(AcceptResponse::vote).collect(Collectors.toSet());
              final var quorumOutcome =
                  quorumStrategy.assessAccepts(logIndex, vs);
              switch (quorumOutcome) {
                case WIN -> {
                  acceptVotesByLogIndex.put(logIndex, AcceptVotes.chosen(acceptVotes.accept()));
                  Optional<Long> highestCommitable = Optional.empty();
                  List<Long> deletable = new ArrayList<>();
                  List<Long> committedSlots = new ArrayList<>();
                  for (final var votesByIdMapEntry :
                      acceptVotesByLogIndex.entrySet()) {
                    if (votesByIdMapEntry.getValue().chosen()) {
                      highestCommitable = Optional.of(votesByIdMapEntry.getKey());
                      deletable.add(votesByIdMapEntry.getKey());
                      committedSlots.add(votesByIdMapEntry.getKey());
                    } else {
                      break;
                    }
                  }
                  if (highestCommitable.isPresent()) {
                    // run the callback
                    for (var slot : committedSlots) {
                      final var accept = journal.loadAccept(slot).orElseThrow();
                      switch (accept) {
                        case Accept(_, _, NoOperation _) -> {
                          // NOOP
                        }
                        case Accept(_, _, Command(final var msgId, final var op)) -> upCall.committed(msgId, op);
                      }
                    }
                    // free the memory
                    for (final var deletableId : deletable) {
                      acceptVotesByLogIndex.remove(deletableId);
                    }
                    // we have committed
                    this.progress = progress.withHighestCommitted(highestCommitable.get());
                    // let the cluster know
                    network.broadcast(new Commit(highestCommitable.get()));
                  }
                  // if we still have missing votes resend the accepts
                  for (var entry : acceptVotesByLogIndex.entrySet().stream()
                      .filter(e -> !e.getValue().chosen()).toList()) {
                    final var responded = entry.getValue().responses().keySet();
                    final var resendNodes = new HashSet<>(members);
                    resendNodes.removeAll(responded);
                    resendNodes.forEach(node -> network.send(node, ack(entry.getValue().accept())));
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
      case PrepareResponse prepareResponse -> {
        if (RECOVER == role) {
          final var catchup = prepareResponse.catchup();
          final long highestCommittedOther = prepareResponse.highestCommittedIndex();
          final long highestCommitted = progress.highestCommitted();
          if (highestCommitted < highestCommittedOther) {
            // we are behind so now try to catch up
            saveCatchup(highestCommittedOther, catchup);
            // this may be evidence of a new leader so back down
            backdown();
          } else {
            if (prepareResponse.vote().to() == nodeIdentifier) {
              final byte from = prepareResponse.from();
              final long logIndex = prepareResponse.vote().logIndex();
              Optional.ofNullable(prepareResponsesByLogIndex.get(logIndex)).ifPresent(
                  votes -> {
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
                        // first issue new prepare messages for higher slots
                        votes.values().stream()
                            .map(PrepareResponse::progress)
                            .map(Progress::highestAccepted)
                            .max(Long::compareTo)
                            .ifPresent(higherAcceptedSlot -> {
                              final long highestLogIndexProbed = prepareResponsesByLogIndex.lastKey();
                              if (higherAcceptedSlot > highestLogIndexProbed) {
                                term.ifPresent(epoch ->
                                    LongStream.range(higherAcceptedSlot + 1, highestLogIndexProbed + 1)
                                        .forEach(slot -> {
                                          prepareResponsesByLogIndex.put(slot, new HashMap<>());
                                          network.broadcast(new Prepare(slot, epoch));
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

                        term.ifPresent(e -> {
                          // use the highest accepted command to issue an Accept
                          Accept accept = new Accept(logIndex, e, highestAcceptedCommand);
                          // issue the accept messages
                          network.broadcast(accept);
                          // create the empty map to track the responses
                          acceptVotesByLogIndex.put(logIndex, new AcceptVotes(accept));
                          // self vote for the Accept
                          selfVoteOnAccept(accept);
                          // we are no long awaiting the prepare for the current slot
                          prepareResponsesByLogIndex.remove(logIndex);
                          // if we have had no evidence of higher accepted values we can promote
                          if (prepareResponsesByLogIndex.isEmpty()) {
                            this.role = LEAD;
                          }
                        });
                      }
                    }
                  }
              );
            }
          }
        }
      }
      case Commit commit -> journal.committed(nodeIdentifier, commit.logIndex());
      case Catchup(final var from, final var highestCommittedOther) ->
          network.send(from, new CatchupResponse(progress.highestCommitted(), loadCatchup(highestCommittedOther)));
      case CatchupResponse(final var highestCommittedOther, final var catchup) ->
          saveCatchup(highestCommittedOther, catchup);
    }
  }

  private void selfVoteOnAccept(Accept accept) {
    if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
      acceptVotesByLogIndex.get(accept.logIndex()).responses().put(nodeIdentifier, nack(accept));
    } else if (equalOrHigherAccept(progress, accept)) {
      // always journal first
      journal.journalAccept(accept);
      if (higherAccept(progress, accept)) {
        // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
        Progress updatedProgress = progress.withHighestPromised(accept.number());
        journal.saveProgress(updatedProgress);
        this.progress = updatedProgress;
      }
    }
  }

  private void saveCatchup(long highestCommittedOther, List<Accept> catchup) {
    for (Accept accept : catchup) {
      final var slot = accept.logIndex();
      if (slot <= highestCommittedOther) {
        continue;
      }
      if (equalOrHigherAccept(progress, accept)) {
        // always journal first
        journal.journalAccept(accept);

        if (higherAccept(progress, accept)) {
          // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
          Progress updatedProgress = progress.withHighestPromised(accept.number());
          journal.saveProgress(updatedProgress);
          this.progress = updatedProgress;
        }
      }
    }
  }

  private List<Accept> loadCatchup(long highestCommittedOther) {
    final long highestCommitted = progress.highestCommitted();
    List<Accept> catchup = new ArrayList<>();
    for (long slot = highestCommitted + 1; slot < highestCommittedOther; slot++) {
      journal.loadAccept(slot).ifPresent(catchup::add);
    }
    return catchup;
  }

  void backdown() {
    this.role = FOLLOW;
    prepareResponsesByLogIndex = new TreeMap<>();
    acceptVotesByLogIndex = new TreeMap<>();
    term = Optional.empty();
  }

  /**
   * Send a positive vote message to the leader.
   *
   * @param accept The accept message to acknowledge.
   */
  AcceptResponse ack(Accept accept) {
    return
        new AcceptResponse(
            new Vote(nodeIdentifier, accept.number().nodeIdentifier(), accept.logIndex(), true)
            , progress);
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param accept The accept message to reject.
   */
  AcceptResponse nack(Accept accept) {
    return new AcceptResponse(
        new Vote(nodeIdentifier, accept.number().nodeIdentifier(), accept.logIndex(), false)
        , progress);
  }

  /**
   * Send a positive prepare response message to the leader.
   *
   * @param prepare The prepare message to acknowledge.
   */
  PrepareResponse ack(Prepare prepare) {
    return new PrepareResponse(
        new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), true),
        progress,
        journal.loadAccept(prepare.logIndex()),
        List.of());
  }

  /**
   * Send a negative prepare response message to the leader.
   *
   * @param prepare The prepare message to reject.
   * @param catchup The list of accept messages to send to the leader.
   */
  PrepareResponse nack(Prepare prepare, List<Accept> catchup) {
    return new PrepareResponse(
        new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), false),
        progress,
        journal.loadAccept(prepare.logIndex()),
        catchup);
  }

  /**
   * Client request to append a command to the log.
   *
   * @param command The command to append.
   * @return The possible log index of the appended command.
   */
  public Optional<Long> startAppendToLog(Command command) {
    if (term.isPresent()) {
      final long slot = progress.highestAccepted() + 1;
      final var accept = new Accept(slot, term.get(), command);
      // FIXME what now?
      return Optional.of(slot);
    } else return Optional.empty();
  }

  static boolean equalOrHigherAccept(Progress progress, Accept accept) {
    return progress.highestPromised().lessThanOrEqualTo(accept.number());
  }

  static boolean higherAcceptForCommittedSlot(Accept accept, Progress progress) {
    return accept.number().greaterThan(progress.highestPromised()) &&
        accept.logIndex() <= progress.highestCommitted();
  }

  static Boolean lowerAccept(Progress progress, Accept accept) {
    return accept.number().lessThan(progress.highestPromised());
  }

  static Boolean higherAccept(Progress progress, Accept accept) {
    return accept.number().greaterThan(progress.highestPromised());
  }
}
