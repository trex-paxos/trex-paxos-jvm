package com.github.trex_paxos;

import java.util.*;

public class PaxosNode {
    final byte nodeIdentifier;
    final Journal journal;
    final Messaging messaging;
    private PaxosRole role = PaxosRole.FOLLOWER;
    Progress progress;
    Long leaderHeartbeat = 0L;
    Long timeout = 0L;
    Optional<BallotNumber> epoch = Optional.empty();
    SortedMap<Identifier, Map<Integer, PrepareResponse>> prepareResponses = new TreeMap<>();
    SortedMap<Identifier, AcceptResponsesAndTimeout> acceptResponses = new TreeMap<>();

    public PaxosNode(byte nodeIdentifier, Journal journal, Messaging messaging) {
        this.nodeIdentifier = nodeIdentifier;
        this.journal = journal;
        this.messaging = messaging;
        this.progress = journal.loadProgress(nodeIdentifier);
    }

    public Set<PaxosMessage> apply(PaxosMessage msg) {
        switch (role) {
            case PaxosRole.FOLLOWER -> {
                switch (msg) {
                    case Prepare prepare -> {
                        switch (PrepareHandler.process(progress, prepare, journal, messaging)) {
                            case StateUpdate(var p, _) -> this.progress = p;
                        }
                    }
                    case Accept accept -> {
                        switch ( AcceptHandler.process(progress, accept, journal, messaging) ){
                            case StateUpdate(var p, _) -> this.progress = p;
                        }
                    }
                    case Commit commit -> {
                        throw new AssertionError("Not implemented");
                    }
                    case Command command -> {
                        // TODO i suspect we need to forward this to the leader.
                        return Set.of();
                    }
                    case NoOperation _ -> {
                        return Set.of();
                    }
                    case PrepareAck _, PrepareNack _, PrepareResponse _, AcceptAck _, AcceptNack _, AcceptResponse _ -> {
                        // ignore as not a leader
                    }
                }
            }
        }
        return null;
    }

}
