package com.github.trex_paxos;

import java.util.*;
import java.util.stream.LongStream;

import static com.github.trex_paxos.AcceptHandler.*;

public class TrexNode {
  final byte nodeIdentifier;
  final Journal journal;
  final Messaging messaging;

  final QuorumStrategy quorumStrategy;

  private TrexRole role = TrexRole.FOLLOWER;
  Progress progress;
  NavigableMap<Identifier, Map<Byte, PrepareResponse>> prepareResponses = new TreeMap<>();
  NavigableMap<Identifier, AcceptVotes> acceptVotesById = new TreeMap<>();

  Optional<BallotNumber> epoch = Optional.empty();

  public TrexNode(byte nodeIdentifier, Journal journal, Messaging messaging, QuorumStrategy quorumStrategy) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.messaging = messaging;
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

  public Set<TrexMessage> apply(TrexMessage msg) {
    // when testing we can check invariants
    assert invariants() : STR."invariants failed: \{this}";

    // main Trex paxos algorithm
    switch (msg) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept.id(), progress)) {
          messaging.nack(accept);
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);
          Progress updatedProgress;
          if (higherAccept(progress, accept)) {
            updatedProgress = progress.withHighestPromised(accept.id().number());
            journal.saveProgress(updatedProgress);
          } else {
            updatedProgress = progress;
          }
          this.progress = updatedProgress;
          messaging.ack(accept); // TODO detect/avoid messages sent to self
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, accept=\{accept}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.id().number();
        if (number.lessThan(progress.highestPromised())) {
          // nack a low prepare
          messaging.nack(prepare);
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher prepare
          final var newProgress = progress.withHighestPromised(prepare.id().number());
          journal.saveProgress(newProgress);
          messaging.ack(prepare);
          this.progress = newProgress;
          // leader or recoverer should give way to a higher prepare
          if (this.role != TrexRole.FOLLOWER)
            backdown();
        } else if (number.equals(progress.highestPromised())) {
          messaging.ack(prepare);
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
              final var quorumOutcome = quorumStrategy.assessAccepts(acceptVotes.responses().values());
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
                    messaging.send(new Commit(highestCommitable, nodeIdentifier));
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
          // TODO if the prepare was a low response we should figure out if there is evidence of a stale leader
          // else issue a high prepare.

          final var id = prepareResponse.requestId();
          final long highestCommitted = progress.highestCommitted().logIndex();
          final long highestCommittedOther = prepareResponse.highestCommittedIndex();
          if (highestCommitted < highestCommittedOther)  {
            // request retransmission of the highest committed
            messaging.send(new RetransmitRequest(nodeIdentifier, prepareResponse.from(), progress.highestCommitted().logIndex()));
            // we cannot lead as we are not up-to-date
            backdown();
          } else {
            if (id.number().nodeIdentifier() == nodeIdentifier) {
              final byte from = prepareResponse.from();
              Optional.ofNullable(prepareResponses.get(id)).ifPresent(
                votes -> {
                  votes.put(from, prepareResponse);
                  final var quorumOutcome = quorumStrategy.assessPromises(votes.values());
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
                        .map(PrepareResponse::highestAcceptedIndex)
                        .max(Long::compareTo)
                        .ifPresent(higherAcceptedSlot -> {
                          final long highestLogIndexProbed = prepareResponses.lastKey().logIndex();
                          if (higherAcceptedSlot > highestLogIndexProbed) {
                            epoch.ifPresent(epoch ->
                              LongStream.range(higherAcceptedSlot + 1, highestLogIndexProbed + 1)
                                .forEach(slot -> {
                                  final var slotId = new Identifier(epoch, slot);
                                  prepareResponses.put(slotId, new HashMap<>());
                                  messaging.prepare(new Prepare(slotId));
                                }));
                          }
                        });

                      // find the highest accepted command if any
                      AbstractCommand highestAcceptedCommand = votes.values().stream()
                        .filter(r -> r instanceof PrepareAck)
                        .map(r -> (PrepareAck) r)
                        .map(PrepareAck::highestUncommitted)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .max(Accept::compareTo)
                        .map(Accept::command)
                        .orElse(NoOperation.NOOP);

                      // use the highest accepted command to issue an accept
                      Accept accept = new Accept(id, highestAcceptedCommand);

                      acceptVotesById.put(id, new AcceptVotes(accept));

                      // issue the accept messages
                      messaging.accept(accept);

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
        throw new AssertionError("not implemented");
      }
      case RetransmitRequest retransmitRequest -> {
        throw new AssertionError("not implemented");
      }
      case RetransmitResponse retransmitResponse -> {
        throw new AssertionError("not implemented");
      }
    }
    return null;
  }

  private void backdown() {
    this.role = TrexRole.FOLLOWER;
    prepareResponses = new TreeMap<>();
    acceptVotesById = new TreeMap<>();
    // TODO: clear timeouts, heartbeats and drop any in flight commands
    // TODO must log this event
  }
}
