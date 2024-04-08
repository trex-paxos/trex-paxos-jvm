package com.github.trex_paxos;

public interface Network {
  void send(byte node, TrexMessage nack);

  void broadcast(TrexMessage commit);
}
