package com.github.trex_paxos;

import java.util.*;

import static com.github.trex_paxos.AcceptHandler.*;

public class TrexNode {
    final byte nodeIdentifier;
    final Journal journal;
    final Messaging messaging;

    final QuorumStrategy quorumStrategy;

    private TrexRole role = TrexRole.FOLLOWER;
    Progress progress;
    Long leaderHeartbeat = 0L;
    Long timeout = 0L;
    Optional<BallotNumber> epoch = Optional.empty();
    NavigableMap<Identifier, Map<Integer, PrepareResponse>> prepareResponses = new TreeMap<>();
    NavigableMap<Identifier, AcceptVotes> acceptVotesById = new TreeMap<>();

    public TrexNode(byte nodeIdentifier, Journal journal, Messaging messaging, QuorumStrategy quorumStrategy) {
        this.nodeIdentifier = nodeIdentifier;
        this.journal = journal;
        this.messaging = messaging;
        this.quorumStrategy = quorumStrategy;
        this.progress = journal.loadProgress(nodeIdentifier);
    }

    public Set<PaxosMessage> apply(PaxosMessage msg) {
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
                    throw new IllegalArgumentException(STR."\{accept.getClass().getCanonicalName()}:\{accept.toString()}");
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
                    throw new AssertionError(String.format("should be unreachable: %s for %s", prepare, progress));
                }
            }
            case AcceptResponse acceptResponse -> {
                if (role != TrexRole.FOLLOWER) {
                    final Identifier highestCommitted = progress.highestCommitted();
                    final Identifier highestCommittedOther = acceptResponse.highestCommitted();
                    if (highestCommitted.compareTo(highestCommittedOther) < 0) {
                        // leader or recoverer should always know the highest committed
                        backdown();
                    } else if (role == TrexRole.LEADER) {
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
                                        if( acceptId.equals(acceptVotesById.firstKey())) {
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
                                            for( final var deletableId : deletable) {
                                                acceptVotesById.remove(deletableId);
                                            }
                                            // we have committed
                                            this.progress = progress.withHighestCommitted(highestCommitable);
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

                        // TODO always check own accept from journal?

                        // TODO should remove committed slot from acceptResponses?

                    } else {
                        // TODO recoverer
                        throw new AssertionError("not implemented");
                    }
                }
            }
            case PrepareResponse _ -> {
                if (role != TrexRole.FOLLOWER) {
                    throw new AssertionError("not implemented");
                }
            }
        }
        return null;
    }

    private void backdown() {
        this.role = TrexRole.FOLLOWER;
        prepareResponses = new TreeMap<>();
        acceptVotesById = new TreeMap<>();
        // TODO: clear timeouts, heartbeats and drop any in flight commands
    }
}
