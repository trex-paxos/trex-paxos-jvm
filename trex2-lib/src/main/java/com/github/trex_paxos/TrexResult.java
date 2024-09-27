package com.github.trex_paxos;

import com.github.trex_paxos.msg.Command;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;

public record TrexResult(List<TrexMessage> messages, List<Command> commands) {
  static TrexResult noResult() {
    return new TrexResult(List.of(), List.of());
  }
}
