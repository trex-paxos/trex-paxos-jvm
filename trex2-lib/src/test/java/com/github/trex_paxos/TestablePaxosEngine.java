package com.github.trex_paxos;

import com.github.trex_paxos.msg.AbstractCommand;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.logging.Level;

abstract class TestablePaxosEngine extends TrexEngine {

  final TransparentJournal journal;

  final TreeMap<Long, AbstractCommand> allCommandsMap = new TreeMap<>();

  public List<AbstractCommand> allCommands() {
    return new ArrayList<>(allCommandsMap.values());
  }

  public TestablePaxosEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, TransparentJournal journal) {
    super(new TrexNode(Level.INFO, nodeIdentifier, quorumStrategy, journal));
    this.journal = journal;
  }

  TrexResult paxos(TrexMessage input) {
    if (input.from() == trexNode.nodeIdentifier()) {
      return TrexResult.noResult();
    }
    LOGGER.finer(() -> trexNode.nodeIdentifier + " <~ " + input);
    final var oldRole = trexNode.getRole();
    final var result = super.paxosNotThreadSafe(input);
    final var newRole = trexNode.getRole();
    if (oldRole != newRole) {
      LOGGER.info(() -> "Node has changed role:" + trexNode.nodeIdentifier() + " == " + newRole);
    }
    if (!result.commands().isEmpty()) {
      allCommandsMap.putAll(result.commands());
    }
    return result;
  }

  @Override
  public String toString() {
    return "TestablePaxosEngine{" +
        trexNode.nodeIdentifier() + "=" +
        trexNode.currentRole().toString() + "," +
        trexNode.progress +
        '}';
  }

  public String role() {
    return trexNode.currentRole().toString();
  }
}
