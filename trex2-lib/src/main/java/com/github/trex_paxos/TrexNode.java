package com.github.trex_paxos;

import java.util.*;

public class TrexNode {
    final byte nodeIdentifier;
    final Journal journal;
    final Messaging messaging;
    private TrexRole role = TrexRole.FOLLOWER;
    Progress progress;
    Long leaderHeartbeat = 0L;
    Long timeout = 0L;
    Optional<BallotNumber> epoch = Optional.empty();
    SortedMap<Identifier, Map<Integer, PrepareResponse>> prepareResponses = new TreeMap<>();
    SortedMap<Identifier, AcceptResponsesAndTimeout> acceptResponses = new TreeMap<>();

    public TrexNode(byte nodeIdentifier, Journal journal, Messaging messaging) {
        this.nodeIdentifier = nodeIdentifier;
        this.journal = journal;
        this.messaging = messaging;
        this.progress = journal.loadProgress(nodeIdentifier);
    }

    public Set<PaxosMessage> apply(PaxosMessage msg) {
        switch (role) {
            case TrexRole.FOLLOWER -> {
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
                    case PrepareAck _, PrepareNack _, PrepareResponse _, AcceptAck _, AcceptNack _, AcceptResponse _ -> {
                        // ignore as not a leader
                    }
                }
            }
        }
        return null;
    }
}
