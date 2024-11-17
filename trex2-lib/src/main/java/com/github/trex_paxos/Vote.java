package com.github.trex_paxos;

import com.github.trex_paxos.msg.BallotNumber;

/// Nodes in a cluster vote for whether an accept message is chosen or not. This object tracks such votes.
public record Vote(byte from, byte to, long logIndex, boolean vote, BallotNumber number) {
}
