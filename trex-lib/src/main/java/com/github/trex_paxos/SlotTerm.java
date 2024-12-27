package com.github.trex_paxos;

/// Each leader must increment the counter in the ballot number each time it attempts to lead. This means that the
/// pairing of a slot with a ballot number is unique. We use this to count votes only against this pairing to know
/// which `accept`is being voted for.
public record SlotTerm(long logIndex, BallotNumber number) {
}
