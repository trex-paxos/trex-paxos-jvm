package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;

/// When a TrexEngine is processed it may return messages to send out and/or results from running the application upCall while
/// holding the mutex. The result of running the upCall must be routed back to the originating client.
public record EngineResult<R>(List<TrexMessage> messages, List<HostResult<R>> results) {
  public EngineResult {
    messages = List.copyOf(messages);
    results = List.copyOf(results);
  }
}
