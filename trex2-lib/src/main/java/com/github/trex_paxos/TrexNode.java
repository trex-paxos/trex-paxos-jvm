package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

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
   * During a recovery we will track all the slots that we are probing to find the highest accepted operationBytes.
   */
  NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  /**
   * When leading we will track the responses to a stream of accept messages.
   */
  NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /**
   * The host application will need to learn that a log index has been chosen.
   */
  final HostApplication hostApplication;

  /**
   * When we are leader we need to now the highest ballot number to use.
   */
  Optional<BallotNumber> term = Optional.empty();

  /**
   * Create a new TrexNode that will load the current progress from the journal. The journal must have been pre-initialised.
   *
   * @param nodeIdentifier  The unique node identifier. This must be unique across the cluster and across enough time for prior messages to have been forgotten.
   * @param quorumStrategy  The quorum strategy that may be a simple majority, else things like FPaxos or UPaxos
   * @param journal         The durable storage and durable log. This must be pre-initialised.
   * @param hostApplication The host application that will be called back when a command is chosen or deal with heartbeats and timeouts.
   */
  public TrexNode(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal, HostApplication hostApplication) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.hostApplication = hostApplication;
    this.progress = journal.loadProgress(nodeIdentifier);
  }

  /**
   * This is the main Paxos Algorithm. It is not public as the timeout logic needs to first intercept Commit messages.
   * @param input The message to process.
   * @return A list of messages to send out to the cluster.
   */
  List<TrexMessage> paxos(TrexMessage input) {
    List<TrexMessage> messages = new ArrayList<>();
    switch (input) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
          messages.add(nack(accept));
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);
          if (higherAccept(progress, accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            Progress updatedProgress = progress.withHighestPromised(accept.number());
            journal.saveProgress(updatedProgress);
            this.progress = updatedProgress;
          }
          messages.add(ack(accept));
        } else {
          throw new AssertionError("unreachable progress={" + progress + "}, accept={" + accept + "}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.number();
        if (number.lessThan(progress.highestPromised()) || prepare.logIndex() <= progress.highestCommitted()) {
          // nack a low prepare else any prepare for a committed slot sending any accepts they are missing
          messages.add(nack(prepare, loadCatchup(prepare.logIndex())));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher prepare
          final var newProgress = progress.withHighestPromised(prepare.number());
          journal.saveProgress(newProgress);
          messages.add(ack(prepare));
          this.progress = newProgress;
          // leader or recoverer should give way to a higher prepare
          if (prepare.number().nodeIdentifier() != nodeIdentifier && role != FOLLOW)
            backdown();
        } else if (number.equals(progress.highestPromised())) {
          messages.add(ack(prepare));
        } else {
          throw new AssertionError("unreachable progress={" + progress + "}, prepare={" + prepare + "}");
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
                        case Accept(_, _, _, NoOperation _) -> {
                          // NOOP
                        }
                        case Accept(_, _, _, final var command) -> hostApplication.upCall((Command) command);
                      }
                    }
                    // free the memory
                    for (final var deletableId : deletable) {
                      acceptVotesByLogIndex.remove(deletableId);
                    }
                    // we have committed
                    this.progress = progress.withHighestCommitted(highestCommitable.get());
                    // let the cluster know
                    messages.add(new Commit(nodeIdentifier, highestCommitable.get()));
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
          if (prepareResponse.catchupResponse().isPresent()) {
            if (prepareResponse.highestUncommitted().isPresent()) {
              //noinspection OptionalGetWithoutIsPresent
              final long highestCommittedOther = prepareResponse.highestCommittedIndex().get();
              final long highestCommitted = progress.highestCommitted();
              if (highestCommitted < highestCommittedOther) {
                // we are behind so now try to catch up
                saveCatchup(prepareResponse.catchupResponse().get());
                // this may be evidence of a new leader so back down
                backdown();
              }
            } else if (prepareResponse.vote().to() == nodeIdentifier) {
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
                        //noinspection OptionalGetWithoutIsPresent
                        votes.values().stream()
                            .filter(p -> p.highestCommittedIndex().isPresent())
                            .map(p -> p.highestCommittedIndex().get())
                            .max(Long::compareTo)
                            .ifPresent(higherAcceptedSlot -> {
                              final long highestLogIndexProbed = prepareResponsesByLogIndex.lastKey();
                              if (higherAcceptedSlot > highestLogIndexProbed) {
                                term.ifPresent(epoch ->
                                    LongStream.range(higherAcceptedSlot + 1, highestLogIndexProbed + 1)
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

                        term.ifPresent(e -> {
                          // use the highest accepted command to issue an Accept
                          Accept accept = new Accept(nodeIdentifier, logIndex, e, highestAcceptedCommand);
                          // issue the accept messages
                          messages.add(accept);
                          // create the empty map to track the responses
                          acceptVotesByLogIndex.put(logIndex, new AcceptVotes(accept));
                          // self vote for the Accept
                          selfVoteOnAccept(accept);
                          // we are no long awaiting the prepare for the current slot
                          prepareResponsesByLogIndex.remove(logIndex);
                          // if we have had no evidence of higher accepted operationBytes we can promote
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
      case Commit(final var from, final var maxSlotCommittable) -> {
        final var lastCommittedIndex = progress.highestCommitted();
        if (maxSlotCommittable > lastCommittedIndex) {
          // we may have gaps, so we must find the ones that we have in the log
          //noinspection OptionalGetWithoutIsPresent
          final var commitableAccepts =
              LongStream.range(lastCommittedIndex + 1, maxSlotCommittable + 1)
                  .mapToObj(journal::loadAccept)
                  .takeWhile(Optional::isPresent)
                  .map(Optional::get)
                  .toList();

          long newHighestCommitted = lastCommittedIndex;

          // make the callback to the main application
          for (var accept : commitableAccepts) {
            switch (accept) {
              case Accept(final var logIndex, _, _, NoOperation _) -> newHighestCommitted = logIndex;
              case Accept(final var logIndex, _, _, final var command) -> {
                newHighestCommitted = logIndex;
                hostApplication.upCall((Command) command);
              }
            }
          }
          if (!commitableAccepts.isEmpty()) {
            progress = progress.withHighestCommitted(commitableAccepts.getLast().logIndex());
            journal.saveProgress(progress);
          }
          // resend message for missing slots
          if (commitableAccepts.size() < maxSlotCommittable - lastCommittedIndex) {
            messages.add(new Catchup(nodeIdentifier, from, newHighestCommitted));
          }
        }
      }
      case Catchup(final var replyTo, final var to, final var highestCommittedOther) -> {
        if (to == nodeIdentifier)
          messages.add(new CatchupResponse(nodeIdentifier, replyTo, progress.highestCommitted(), loadCatchup(highestCommittedOther)));
      }
      case CatchupResponse(_, final var to, _, _) -> {
        if (to == nodeIdentifier)
          saveCatchup((CatchupResponse) input);
      }
    }
    return messages;
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

  private void saveCatchup(CatchupResponse catchupResponse) {
    for (Accept accept : catchupResponse.catchup()) {
      final var slot = accept.logIndex();
      if (slot <= catchupResponse.highestCommittedIndex()) {
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
        journal.loadAccept(prepare.logIndex()),
        Optional.empty());
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
        journal.loadAccept(prepare.logIndex()),
        Optional.of(new CatchupResponse(nodeIdentifier, prepare.from(), progress.highestCommitted(), catchup)));
  }

  /**
   * Client request to append a command to the log. TODO is this needed?
   *
   * @param command The command to append.
   * @return An accept for the next unassigned slot in the log at this leader.
   */
  Optional<Accept> startAppendToLog(Command command) {
    assert role == LEAD : "role={" + role + "}";
    if (term.isPresent()) {
      final long slot = progress.highestAccepted() + 1;
      final var accept = new Accept(nodeIdentifier, slot, term.get(), command);
      // this could self accept else self reject
      final var actOrNack = this.paxos(accept);
      assert actOrNack.size() == 1 : "accept response should be a single messages={" + actOrNack + "}";
      // update state on the self accept or reject
      final var updated = this.paxos(actOrNack.getFirst());
      // we should not have any messages to send as we have not sent out the message to get a commit.
      assert updated.isEmpty() : "updated should be empty={" + updated + "}";
      // return the Accept which should be sent out to the cluster.
      return Optional.of(accept);
    } else
      return Optional.empty();
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

  @SuppressWarnings("unused")
  public long highestCommitted() {
    return progress.highestCommitted();
  }

  @SuppressWarnings("unused")
  public byte nodeIdentifier() {
    return nodeIdentifier;
  }

  Optional<Prepare> timeout() {
    if (role == FOLLOW) {
      role = RECOVER;
      term = Optional.of(new BallotNumber(progress.highestPromised().counter() + 1, nodeIdentifier));
      final var prepare = new Prepare(nodeIdentifier, progress.highestCommitted() + 1, term.get());
      final var selfPrepareResponse = paxos(prepare);
      assert selfPrepareResponse.size() == 1 : "selfPrepare={" + selfPrepareResponse + "}";
      return Optional.of(prepare);
    }
    return Optional.empty();
  }

  public boolean isLeader() {
    return role.equals(LEAD);
  }

  Commit heartbeatCommit() {
    return new Commit(nodeIdentifier, progress.highestCommitted());
  }

  public TrexRole getRole() {
    return role;
  }
}
