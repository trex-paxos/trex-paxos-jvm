package com.github.trex_paxos;

public sealed interface PrepareResponse extends TrexMessage permits PrepareNack, PrepareAck {
    byte from();
    Identifier requestId();

    long highestAcceptedIndex();

  long highestCommittedIndex();
}
