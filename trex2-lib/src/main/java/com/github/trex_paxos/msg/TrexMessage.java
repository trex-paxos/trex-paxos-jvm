package com.github.trex_paxos.msg;

import java.io.DataOutputStream;
import java.io.IOException;

public sealed interface TrexMessage extends Message permits Accept,
    AcceptResponse,
    BroadcastMessage,
    Catchup,
    CatchupResponse,
    Commit,
    DirectMessage,
    Prepare,
    PrepareResponse {

  void writeTo(DataOutputStream dos) throws IOException;

  byte from();

}

