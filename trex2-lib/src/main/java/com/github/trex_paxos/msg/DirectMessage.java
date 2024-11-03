package com.github.trex_paxos.msg;

public sealed interface DirectMessage extends TrexMessage permits AcceptResponse, Catchup, CatchupResponse, PrepareResponse {
  byte to();
}
