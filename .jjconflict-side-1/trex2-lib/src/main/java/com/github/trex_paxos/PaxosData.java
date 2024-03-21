package com.github.trex_paxos;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

public record PaxosData(Progress progress,
                        Long leaderHeartbeat,
                        Long timeout,
                        SortedMap<Identifier, Map<Integer, PrepareResponse>> prepareResponses,
                        Optional<BallotNumber> epoch,
                        SortedMap<Identifier, AcceptResponsesAndTimeout> acceptResponses) {

    public static SortedMap<Identifier, Map<Integer, PrepareResponse>> emptyPrepares() {
        throw new AssertionError("Not implemented");
    }

    public static SortedMap<Identifier, AcceptResponsesAndTimeout> emptyAccepts() {
        throw new AssertionError("Not implemented");
    }

    public PaxosData {
        prepareResponses = prepareResponses != null ? prepareResponses : emptyPrepares();
        epoch = epoch != null ? epoch : Optional.empty();
        acceptResponses = acceptResponses != null ? acceptResponses : emptyAccepts();
    }
}
