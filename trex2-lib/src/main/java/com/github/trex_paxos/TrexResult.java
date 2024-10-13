package com.github.trex_paxos;

import com.github.trex_paxos.msg.Command;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;

/// The result of running paxos is a list of messages and a list of commands.
///
/// @param messages A possibly empty list of messages that were generated during the paxos run.
/// @param commands A possibly empty list of chosen aka fixed aka commited commands.
public record TrexResult(List<TrexMessage> messages, List<Command> commands) {
  static TrexResult noResult() {
    return new TrexResult(List.of(), List.of());
  }
}
