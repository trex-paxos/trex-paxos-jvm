package com.github.trex_paxos.msg;

public sealed interface DirectMessage extends TrexMessage permits
    PrepareResponse,
    AcceptResponse,
    Catchup,
    CatchupResponse {
  byte to();
}
