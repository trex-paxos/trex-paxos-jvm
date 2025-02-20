package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import static com.github.trex_paxos.TrexLogger.LOGGER;

abstract class TestablePaxosEngine extends TrexEngine {

  final TransparentJournal journal;

  final TreeMap<Long, AbstractCommand> allCommandsMap = new TreeMap<>();

  public List<AbstractCommand> allCommands() {
    return new ArrayList<>(allCommandsMap.values());
  }


  public TestablePaxosEngine(
      short nodeIdentifier,
      QuorumStrategy quorumStrategy,
      TransparentJournal journal,
      BiConsumer<Long, Command> commitCallback
  ) {
    // Pass commitCallback to super constructor
    super(new TrexNode(Level.INFO, nodeIdentifier, quorumStrategy, journal), commitCallback);
    this.journal = journal;
  }


  @Override
  public TrexResult paxos(List<TrexMessage> input) {
    LOGGER.finer(() -> trexNode.nodeIdentifier + " <~ " + input);
    final var oldRole = trexNode.getRole();
    final var result = super.paxos(input);
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
