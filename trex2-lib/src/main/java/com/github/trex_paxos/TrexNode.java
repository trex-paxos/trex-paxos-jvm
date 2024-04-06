package com.github.trex_paxos;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.github.trex_paxos.AcceptHandler.*;

public class TrexNode {
  final byte nodeIdentifier;
  final Journal journal;

  final QuorumStrategy quorumStrategy;

  private TrexRole role = TrexRole.FOLLOWER;
  Progress progress;
  NavigableMap<Identifier, Map<Byte, PrepareResponse>> prepareResponses = new TreeMap<>();
  NavigableMap<Identifier, AcceptVotes> acceptVotesById = new TreeMap<>();

  Optional<BallotNumber> epoch = Optional.empty();

  public TrexNode(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.progress = journal.loadProgress(nodeIdentifier);
  }

  private boolean invariants() {
    return switch (role) {
      case FOLLOWER -> // follower is tracking no state and has no epoch
        epoch.isEmpty() && prepareResponses.isEmpty() && acceptVotesById.isEmpty();
      case CANIDATE -> // candidate has an epoch and is tracking some prepare responses and/or some accept votes
        epoch.isPresent() && (!prepareResponses.isEmpty() || !acceptVotesById.isEmpty());
      case LEADER -> // leader has an epoch and is tracking no prepare responses
        epoch.isPresent() && prepareResponses.isEmpty();
    };
  }

  private List<TrexMessage> messageToSend = new ArrayList();

  /**
   * The main entry point for the Trex paxos algorithm.
   */
  public List<TrexMessage> paxos(TrexMessage msg) {
    // when testing we can check invariants
    assert invariants() : STR."invariants failed: \{this.role} \{this.epoch} \{this.prepareResponses} \{this.acceptVotesById}";

    messageToSend.clear();

    switch (msg) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept.id(), progress)) {
          messageToSend.add(nack(accept));
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);

          if (higherAccept(progress, accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            Progress updatedProgress = progress.withHighestPromised(accept.id().number());
            journal.saveProgress(updatedProgress);
            this.progress = updatedProgress;
          }

          messageToSend.add(ack(accept)); // TODO detect/avoid messages sent to self
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, accept=\{accept}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.id().number();
        if (number.lessThan(progress.highestPromised()) || prepare.id().logIndex() <= progress.highestCommitted().logIndex()) {
          // nack a low prepare else any prepare for a committed slot sending any accepts they are missing
          final long highestCommitted = progress.highestCommitted().logIndex();
          final long highestCommittedOther = prepare.id().logIndex();
          List<Accept> catchup = new ArrayList<>();
          for (long slot = highestCommitted + 1; slot < highestCommittedOther; slot++) {
            journal.loadAccept(slot).ifPresent(catchup::add);
          }
          // FIXME add highestAccepted to progress and speculatively send those.
          messageToSend.add(nack(prepare, catchup));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher prepare
          final var newProgress = progress.withHighestPromised(prepare.id().number());
          journal.saveProgress(newProgress);
          messageToSend.add(ack(prepare));
          this.progress = newProgress;
          // leader or recoverer should give way to a higher prepare
          if (this.role != TrexRole.FOLLOWER)
            backdown();
        } else if (number.equals(progress.highestPromised())) {
          messageToSend.add(ack(prepare));
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, prepare=\{prepare}");
        }
      }
      case AcceptResponse acceptResponse -> {
        assert acceptResponse.requestId().number().nodeIdentifier() == nodeIdentifier : "should only receive responses for requests we made";

        if (TrexRole.FOLLOWER != role) {
          // Both Leader and Candidate can receive AcceptResponses
          final var id = acceptResponse.requestId();
          Optional.ofNullable(this.acceptVotesById.get(id)).ifPresent(acceptVotes -> {
            if (!acceptVotes.chosen()) {
              acceptVotes.responses().put(acceptResponse.from(), acceptResponse);
              Collection<Vote> vs = acceptVotes.responses().values().stream()
                .map(AcceptResponse::vote).collect(Collectors.toCollection(ArrayList::new));
              final var quorumOutcome =
                quorumStrategy.assessAccepts(vs);
              switch (quorumOutcome) {
                case QUORUM -> {
                  final var acceptId = acceptVotes.accept().id();
                  acceptVotesById.put(acceptId, AcceptVotes.chosen(acceptVotes.accept()));
                  // out of order messages mean we must scan to find all chosen accepts
                  if (acceptId.equals(acceptVotesById.firstKey())) {
                    Identifier highestCommitable = acceptId;
                    List<Identifier> deletable = new ArrayList<>();
                    for (final var votesByIdMapEntry :
                      acceptVotesById.headMap(acceptId, true).entrySet()) {
                      // FIXME is this correct?
                      if (votesByIdMapEntry.getValue().chosen()) {
                        highestCommitable = votesByIdMapEntry.getKey();
                        deletable.add(votesByIdMapEntry.getKey());
                      } else {
                        break;
                      }
                    }
                    // free the memory
                    for (final var deletableId : deletable) {
                      acceptVotesById.remove(deletableId);
                    }
                    // we have committed
                    this.progress = progress.withHighestCommitted(highestCommitable);
                    // let the cluster know
                    messageToSend.add(new Commit(highestCommitable, nodeIdentifier));
                  }
                }
                case NO_DECISION -> {
                  // do nothing as a quorum has not yet been reached.
                }
                case NO_QUORUM -> {
                  // we are unable to achieve a quorum, so we must back down
                  backdown();
                }
              }
            }
          });
        }
      }
      case PrepareResponse prepareResponse -> {
        if (TrexRole.CANIDATE == role ) {

          final var id = prepareResponse.requestId();
          final long highestCommitted = progress.highestCommitted().logIndex();
          final long highestCommittedOther = prepareResponse.highestCommittedIndex();
          if (highestCommitted < highestCommittedOther)  {
            // we are not up-to-date in terms of commit so another leader may be active. now try to catch up
            for (Accept accept : prepareResponse.catchup()) {
              final var slot = accept.id().logIndex();
              if (slot <= highestCommitted) {
                continue;
              }
              if (equalOrHigherAccept(progress, accept)) {
                // always journal first
                journal.journalAccept(accept);

                if (higherAccept(progress, accept)) {
                  // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
                  Progress updatedProgress = progress.withHighestPromised(accept.id().number());
                  journal.saveProgress(updatedProgress);
                  this.progress = updatedProgress;
                }
              }
            }
            backdown();
          } else {
            if (id.number().nodeIdentifier() == nodeIdentifier) {
              final byte from = prepareResponse.from();
              Optional.ofNullable(prepareResponses.get(id)).ifPresent(
                votes -> {
                  votes.put(from, prepareResponse);
                  Collection<Vote> vs = votes.values().stream()
                    .map(PrepareResponse::vote).collect(Collectors.toCollection(ArrayList::new));
                  final var quorumOutcome = quorumStrategy.assessPromises(vs);
                  switch (quorumOutcome) {
                    case NO_DECISION ->
                      // do nothing as a quorum has not yet been reached.
                      prepareResponses.put(id, votes);
                    case NO_QUORUM ->
                      // we are unable to achieve a quorum, so we must back down
                      backdown();
                    case QUORUM -> {
                      // first issue new prepare messages for higher slots
                      votes.values().stream()
                        .map(PrepareResponse::progress)
                        .map(Progress::highestAccepted)
                        .max(Long::compareTo)
                        .ifPresent(higherAcceptedSlot -> {
                          final long highestLogIndexProbed = prepareResponses.lastKey().logIndex();
                          if (higherAcceptedSlot > highestLogIndexProbed) {
                            epoch.ifPresent(epoch ->
                              LongStream.range(higherAcceptedSlot + 1, highestLogIndexProbed + 1)
                                .forEach(slot -> {
                                  final var slotId = new Identifier(epoch, slot);
                                  prepareResponses.put(slotId, new HashMap<>());
                                  messageToSend.add(new Prepare(slotId));
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

                      // use the highest accepted command to issue an accept
                      Accept accept = new Accept(id, highestAcceptedCommand);

                      acceptVotesById.put(id, new AcceptVotes(accept));

                      // issue the accept messages
                      messageToSend.add(accept);

                      // we are no long awaiting the prepare for the current slot
                      prepareResponses.remove(id);

                      // if we have had no evidence of higher accepted values we can promote
                      if( prepareResponses.isEmpty() ){
                        this.role = TrexRole.LEADER;
                      }
                    }
                  }
                }
              );
            }
          }
        }
      }
      case Commit commit -> {
        journal.commit(commit.identifier().logIndex());
      }
    }
    return null;
  }

  void backdown() {
    this.role = TrexRole.FOLLOWER;
    prepareResponses = new TreeMap<>();
    acceptVotesById = new TreeMap<>();
  }

  /**
   * Send a positive vote message to the leader.
   *
   * @param accept The accept message to acknowledge.
   */
  AcceptResponse ack(Accept accept) {
    return new AcceptResponse(new Vote(nodeIdentifier, accept.id(), true), progress);
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param accept The accept message to reject.
   */
  AcceptResponse nack(Accept accept) {
    return new AcceptResponse(new Vote(nodeIdentifier, accept.id(), false), progress);
  }

  /**
   * Send a positive prepare response message to the leader.
   *
   * @param prepare The prepare message to acknowledge.
   */
  PrepareResponse ack(Prepare prepare) {
    return new PrepareResponse(
      new Vote(nodeIdentifier, prepare.id(), true),
      progress,
      journal.loadAccept(prepare.id().logIndex()),
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
      new Vote(nodeIdentifier, prepare.id(), false),
      progress,
      journal.loadAccept(prepare.id().logIndex()),
      catchup);
  }
}
