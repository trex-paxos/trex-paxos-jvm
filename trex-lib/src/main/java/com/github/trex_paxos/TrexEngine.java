package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.NodeId;
import org.jetbrains.annotations.TestOnly;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.github.trex_paxos.TrexLogger.LOGGER;

/// TrexEngine manages consensus ordering and application state updates under mutex protection.
/// It is closable to use try-with-resources to ensure that the TrexNode is closed properly on exceptions due to bad
/// data or journal write exceptions.
/// The core paxos algorithm is implemented in the TrexNode class that is wrapped by this class.
public class TrexEngine<RESULT> implements AutoCloseable {
  /// The underlying TrexNode that is the actual Part-time Parliament algorithm implementation guarded by this class.
  final protected TrexNode trexNode;

  final NodeId nodeId;

  /// The callback to the host application to apply the chosen results to the application state.
  final protected BiFunction<Long, Command, RESULT> upCallUnderMutex;

  public TrexEngine(
      TrexNode trexNode,
      BiFunction<Long, Command, RESULT> upCall) {
    this.trexNode = trexNode;
    this.nodeId = new NodeId(trexNode.nodeIdentifier());
    this.upCallUnderMutex = upCall;
  }

  private final Semaphore mutex = new Semaphore(1);

  /// The main entry point for the Trex paxos algorithm. This method is thread safe and allows only one thread at a
  /// time. The following may happen:
  ///
  /// 1. The message may be ignored when safe to do so (e.g. slot higher than known fixed or late vote when vote has been won or lost).
  /// 2. As a side effect the {@link Journal} will be updated. See all the warnings in the Journal interface javadoc.
  /// 3. Internally the {@link TrexNode} will recurse once or twice with the mutex is held when it generates a broadcast message that
  /// it instantaneously sends to itself and processes its own responses to those messages such as voting for itself.
  /// 4. The {@link TrexNode} will return a list of messages to send out to the cluster and/or a list of selected Commands.
  /// 5. While the mutex is held the {@link #upCallUnderMutex} callback will be called to apply the chosen results in order to update the application state.
  /// 6. Any results of applying the command to th application state will be passed back to the application.
  /// 7. The {@link Journal#sync()} will be called to make the journal crash durable.
  /// 8. The mutex will be released.
  ///
  /// @param trexMessages The batch of messages to process.
  /// @return A list of messages to send out to the cluster and/or a list of selected Commands.
  public TrexResult paxos(List<TrexMessage> trexMessages) {
    try {
      mutex.acquire();
      try {
        final var chosen = trexMessages
            .stream()
            .filter(m -> m.from() != trexNode.nodeIdentifier())
            .map(this::paxosNotThreadSafe)
            .toList();

        var combined = TrexResult.merge(chosen);

        // Apply results under mutex protection to ensure that identical primary ordering is maintained on all nodes.
        for (Map.Entry<Long, AbstractCommand> entry : combined.results().entrySet()) {
          if (entry.getValue() instanceof Command cmd) {
            final var host = upCallUnderMutex.apply(entry.getKey(), cmd);
          }
        }
        // Here we call sync which is intended to make the journal crash durable.
        // If you are using a framework like spring or JEE this method might do nothing as you are using a transaction manager.
        trexNode.journal.sync();
        return combined;
      } finally {
        mutex.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warning("TrexEngine was interrupted awaiting the mutex probably to shutdown while under load.");
      trexNode.crash();
      return TrexResult.noResult();
    }
  }

  private TrexResult paxosNotThreadSafe(TrexMessage msg) {
    if (msg == null || msg.from() == trexNode.nodeIdentifier()) {
      LOGGER.finer(() -> nodeId.id() + " dropping message " + msg);
      return TrexResult.noResult();
    }

    LOGGER.finer(() -> trexNode.nodeIdentifier + " <~ " + msg);
    final var oldRole = trexNode.getRole();
    final var result = trexNode.paxos(msg);
    final var newRole = trexNode.getRole();
    if (oldRole != newRole) {
      LOGGER.info(() -> "Node has changed role:" + trexNode.nodeIdentifier() + " == " + newRole);
    }
    return result;
  }

  /// Create the next leader batch of messages for the given set of results. This should be called by the host application
  /// when {@link #isLeader()} is true.
  public List<TrexMessage> nextLeaderBatchOfMessages(List<Command> commands) {
    if (trexNode.isFollow()) {
      LOGGER.fine(() -> "node " + nodeIdentifier() + " ignoring nextLeaderBatchOfMessages as not leader");
      return List.of();
    }
    return Stream.concat(
        commands.stream().map(this::nextAcceptMessage),
        Stream.of(currentFixedMessage())
    ).toList();
  }

  private TrexMessage nextAcceptMessage(Command command) {
    LOGGER.fine(() -> "node " + nodeIdentifier() + " processing next command " + command.uuid());
    final var nextAcceptMessage = trexNode.nextAcceptMessage(command);
    // FIXME: pull this out so that we can run it as a batch
    final var r = trexNode.paxos(nextAcceptMessage);
    assert r.results().isEmpty() && r.messages().size() == 1
        && r.messages().getFirst() instanceof AcceptResponse;
    return nextAcceptMessage;
  }

  public Fixed currentFixedMessage() {
    return trexNode.currentFixedMessage();
  }

  public boolean isLeader() {
    return trexNode.isLeader();
  }

  public TrexNode.TrexRole getRole() {
    return trexNode.getRole();
  }

  public short nodeIdentifier() {
    return trexNode.nodeIdentifier();
  }

  @Override
  public void close() {
    LOGGER.info("Closing TrexEngine. We are marking TrexNode as stopped.");
    trexNode.close();
  }

  public void crash() {
    LOGGER.severe("Crashing TrexEngine. We are marking TrexNode as crashed.");
    trexNode.crash();
  }

  public boolean isClosed() {
    return trexNode.isClosed();
  }

  @SuppressWarnings("unused")
  public boolean isCrashed() {
    return trexNode.isCrashed();
  }

  public NodeId nodeId() {
    return nodeId;
  }

  @TestOnly
  public TrexNode trexNode() {
    return trexNode;
  }

  @TestOnly
  public void setLeader() {
    trexNode.setLeader();
  }
}
