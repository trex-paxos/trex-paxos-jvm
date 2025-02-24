package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;

public record EngineResult<R>(List<TrexMessage> messages, List<HostResult<R>> results) {
  public EngineResult {
    messages = List.copyOf(messages);
    results = List.copyOf(results);
  }
}
