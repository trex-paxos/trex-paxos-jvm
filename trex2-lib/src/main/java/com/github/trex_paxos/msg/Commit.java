package com.github.trex_paxos.msg;

/// A leader sends out a Commit when it learns of a new fixed log index. It will also heartbeat this message to keep
/// the followers from timing out. If a node was isolated and rejoins it will learn that it has missed out on some
/// log indexes and will request a Catchup.
///
/// @param from              The node identifier of the leader.
/// @param number            The ballot number of the accepted log entry. The follower must request retransmission if
///                          it does not have the correct accept.
/// @param committedLogIndex The highest contiguous log index that the leader has learnt to have been fixed and so
///                          committed.
/// @param highestFixedLogIndex The highest log index that the leader has learnt to have been fixed. This may be the
///                             same as committedLogIndex. Due to lost messages there may be a gap between the two.
public record Commit(
    byte from,
    BallotNumber number,
    long committedLogIndex
) implements TrexMessage, BroadcastMessage {

}
