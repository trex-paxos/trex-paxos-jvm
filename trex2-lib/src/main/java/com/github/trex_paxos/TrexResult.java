package com.github.trex_paxos;

import com.github.trex_paxos.msg.AbstractCommand;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.Map;

/// The result of running paxos is a list of messages and a list of commands.
///
/// @param messages A possibly empty list of messages that were generated during the paxos run.
/// @param commands A possibly empty list of chosen aka fixed aka commited commands.
public record TrexResult(List<TrexMessage> messages, Map<Long, AbstractCommand> commands) {
  public TrexResult {
    messages = List.copyOf(messages);
    commands = Map.copyOf(commands);
  }
  static TrexResult noResult() {
    return new TrexResult(List.of(), Map.of());
  }
}
