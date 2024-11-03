package com.github.trex_paxos.msg;

public sealed interface TrexMessage extends Message permits Accept, AcceptResponse, BroadcastMessage, Catchup, CatchupResponse, Commit, DirectMessage, Prepare, PrepareResponse {
  byte from();

}

