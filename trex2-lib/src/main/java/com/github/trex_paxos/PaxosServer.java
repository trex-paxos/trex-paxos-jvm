package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class PaxosServer {
  private final Consumer<List<TrexMessage>> networkOutboundSockets;
  private final Supplier<? extends TrexEngine> engine;

  public PaxosServer(Supplier<? extends TrexEngine> engine,
                     final Consumer<List<TrexMessage>> networkOutboundSockets) {
    this.engine = engine;
    this.networkOutboundSockets = networkOutboundSockets;
  }

  // FIXME we should have removed the circular dependency between PaxosServer and TrexEngine so can make this a final field.
  public long nodeId() {
    return engine.get().trexNode.nodeIdentifier();
  }

  public void nextLeaderBatchOfMessages(List<Command> command) {
    final var messages = engine.get().nextLeaderBatchOfMessages(command);
    networkOutboundSockets.accept(messages);
  }

  public abstract void upCall(long slot, Command command);

  public List<TrexMessage> paxosThenUpCall(List<@NotNull TrexMessage> dm) {
    final var result = engine.get().paxos(dm);
    if (!result.commands().isEmpty()) {
      result
          .commands()
          .entrySet()
          .stream()
          .filter(entry -> entry.getValue() instanceof Command)
          .forEach(entry -> upCall(entry.getKey(), (Command) entry.getValue()));
    }
    return result.messages();
  }
}
