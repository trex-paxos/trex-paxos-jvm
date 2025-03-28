package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.stream.Stream;

import static com.github.trex_paxos.ErrorStrings.CRASHED;
import static com.github.trex_paxos.TrexLogger.LOGGER;

/// Manages thread safety and coordinates between the network layer, TrexNode consensus core algorithm,
/// and application callbacks. Ensures:
/// - Single-threaded access to TrexNode via mutex
/// - Ordered application of committed commands via upCallUnderMutex
/// - Crash durability through Journal synchronization
/// - Leader proxy handling and message transmission
/// It is closable to use try-with-resources to ensure that both the TrexNode and the network layer is closed properly on exceptions due to bad
/// data or journal write exceptions.
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

  /// The Semaphore acts as a mutex with:
  /// - Non-reentrant locking
  /// - Fair queuing of threads
  /// - Automatic release on close/crash
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
  /// @return A list of messages to send out to the cluster and the results of the host callback by the client command uuid.
  public EngineResult<RESULT> paxos(List<TrexMessage> trexMessages) {
    try {
      mutex.acquire();
      final var hostResults = new ArrayList<HostResult<RESULT>>();
      final var combinedMessages = new ArrayList<TrexMessage>();

      for (var msg : trexMessages) {
        final var result = paxosNotThreadSafe(msg);
        combinedMessages.addAll(result.messages());
        // Process any fixed commands and get host results
        for (var entry : result.results().entrySet()) {
          long slot = entry.getKey();
          AbstractCommand cmd = entry.getValue();
          if (cmd instanceof Command command) {
            final var host = upCallUnderMutex.apply(entry.getKey(), command);
            final var hostResult = new HostResult<>(slot, command.uuid(), host);
            hostResults.add(hostResult);
          }
        }
      }

      try {
        // Here we call sync which is intended to make the journal crash durable.
        // If you are using a framework like spring or JEE this method might do nothing when you are using a transaction manager.
        trexNode.journal.sync();
      } catch (Exception e) {
        // Log that we are crashing and log the reason.
        LOGGER.log(Level.SEVERE, CRASHED + e, e);
        // In case the application developer has not correctly configured logging JUL logging we log to stderr.
        System.err.println(CRASHED + e);
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        throw e;
      }
      // return the combined messages and the host results
      return new EngineResult<>(combinedMessages, hostResults);
    } catch (InterruptedException e) {
      // FIXME i am not sure what do to in this case
      Thread.currentThread().interrupt();
      LOGGER.warning("TrexEngine was interrupted probably to shutdown while under load so we will close.");
      trexNode().close();
      return new EngineResult<>(List.of(), List.of());
    } finally {
      mutex.release();
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

  /// Marks this node as crashed. The node must be restarted to re reinitialise state from the Journal.
  /// This should be called if there were any exceptions thrown by the Journal. It should also be called
  /// if there were any none recoverable errors thrown from the host application callback.
  ///   /// node as crashed.
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

  @SuppressWarnings("unused")
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
