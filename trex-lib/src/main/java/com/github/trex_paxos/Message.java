package com.github.trex_paxos;

public interface Message {    
    /// @return the node in the cluster that sent this message.
  byte from();
}
