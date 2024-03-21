package com.github.trex_paxos;

public sealed interface PrepareResponse extends PaxosMessage permits PrepareNack, PrepareAck {
}
