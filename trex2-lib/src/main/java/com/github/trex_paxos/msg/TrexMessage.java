package com.github.trex_paxos.msg;

import java.io.DataOutputStream;
import java.io.IOException;

public sealed interface TrexMessage permits Accept, AcceptResponse, Catchup, CatchupResponse, Commit, Prepare, PrepareResponse {
  void writeTo(DataOutputStream dos) throws IOException;

  byte from();

}
