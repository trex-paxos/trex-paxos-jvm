package com.github.trex_paxos.msg;

public sealed interface DirectMessage extends TrexMessage permits AcceptResponse, Catchup, PrepareResponse {
  byte to();
}
