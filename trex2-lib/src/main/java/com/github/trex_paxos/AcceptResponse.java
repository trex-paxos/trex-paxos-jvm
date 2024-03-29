package com.github.trex_paxos;

public sealed interface AcceptResponse extends PaxosMessage permits AcceptAck, AcceptNack {
}
