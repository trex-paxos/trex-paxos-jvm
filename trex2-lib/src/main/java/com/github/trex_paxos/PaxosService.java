package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/// The PaxosService is the base class for running the server side logic of the host application.
/// The network distributed nature of the Paxos algorithm means that it is an RPC system.
/// This class is defined in terms of the input client commands `CMD` and output `RESULT` values.
///
/// This is the abstract class for the embeddable the Paxos Server that will:
///
/// 1. Receive the inbound client command values of generic type `CMD`.
/// 1. Convert commands to `byte[]` within `Command` objects.
/// 1. Run {@link #createAndSendLeaderMessages(List)} to convert them into `accept` messages. This is how the distinguished leader assigns a primary ordering.
/// 1. Receive intra-cluster {@link TrexMessage} messages from the other node s in the same Trex cluster.
/// 1. Run all the messages through the {@link TrexEngine} to get a set of {@link TrexResult} values.
/// 1. Process any fixed command value returned by the engine to then {@link #upCall(Long, Command)} them to update the host application state.
/// 1. Convert any response values of generic type `RESULT` into a byte array to be sent back to the client.
/// 1. Optional: ensure that the {@link Journal} data is persisted to disk by commiting database transactions or {@link Journal#sync()}
/// 1. Push the outbound Paxos messages to go out over the network.
///
/// It is important to note the thread that gets the client command won't return the result to the client. Only when
/// at least one message has been exchanged in a three node cluster will value be fixed. The thread processing the
/// response in one noe of the cluster will respond to the client. The other nodes will run the exact same commands
/// to update state but will not respond to the client. This is the nature of the Paxos algorithm.
///
/// @param <CMD>    The client command value that will be fixed in a slot.
/// @param <RESULT> The return value of the command that will be sent back to the client.
public class PaxosService<CMD, RESULT> {

  static final Logger LOGGER = Logger.getLogger("");

  /// We will keep a map of futures that we will complete when we have run the command value against the lock store.
  /// We actually want to store the future along with the time sent the command and order by time ascending. Then we
  /// can check for old records to see if they have timed out and return exceptionally. We will also need a timeout thread.
  protected final ConcurrentNavigableMap<String, CompletableFuture<RESULT>> replyToClientFutures
      = new ConcurrentSkipListMap<>();

  /// The consumer of a list of outbound `TrexMessage` that it will be sent out over the network.
  private final Consumer<List<TrexMessage>> networkOutboundSockets;

  /// The `TrexEngine` that will run the Paxos algorithm. This is responsible for handling timeouts and heartbeats. It guards a `TrexNode` that has the `algorithm` method.
  private final TrexEngine engine;

  /// We will use virtual threads process client messages and push them out to the other nodes in the Paxos cluster.
  protected final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /// The host application logic that will be called when a command value is fixed in a slot.
  /// The Paxos algorithm ensures that the same command value is fixed in the same slot number on all nodes in the cluster.
  /// Due to NOOPs being fixed or cluster reconfiguration admin commands being fixed into slots the slot number will jump by more than one.
  /// @param slot The slot number that the command value was fixed in.
  /// @param cmd  The client command value that was fixed in the slot. 
  /// @return The result of the command that will be sent back to the client.
  protected final BiFunction<Long, CMD, RESULT> host;

  /// The SerDe for the client command value.
  protected final SerDe<CMD> serdeCmd;

  /// The SerDe for the result value.
  protected final SerDe<RESULT> serdeResult;

  /// Create a new PaxosService with an engine and an outbound consumer of messages that come out of the engine.
  ///
  /// @param engine                 The `TrexEngine` that will run the Paxos algorithm. This is responsible for handling timeouts and heartbeats. It guards a `TrexNode` that has the `algorithm` method.
  /// @param host                   The host application logic that will be called when a command value is fixed in a slot.
  /// @param serdeCmd               The SerDe for the client command value.
  /// @param serdeResult            The SerDe for the result value.
  public PaxosService(TrexEngine engine,
                        final BiFunction<Long, CMD, RESULT> host,
                        final SerDe<CMD> serdeCmd,
                        final SerDe<RESULT> serdeResult,
                        final Consumer<List<TrexMessage>> networkOutboundSockets) {
      this.engine = engine;
      this.host = host;
      this.serdeResult = serdeResult;
      this.serdeCmd = serdeCmd;
      this.networkOutboundSockets = networkOutboundSockets;
  }

  /// Shorthand to get the node id which must be unique in the paxos cluster. It is th responsibility of the cluster owner to ensure that it is unique.
  public byte nodeId() {
    return engine.trexNode.nodeIdentifier();
  }

  /// This will run the Paxos algorithm on the inbound messages. It will return fixed commands and a list of messages
  /// that should be sent out to the network.
  public List<TrexMessage> paxosThenUpCall(List<@NotNull TrexMessage> dm) {
    final var result = engine.paxos(dm);
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

  private void upCall(Long slot, Command command) {
    // we need the clientMsgUuid to complete the future if and only if this is the node that the client sent the command to
    final String clientMsgUuid = command.clientMsgUuid();
    // unpickle host application command
    final var value = serdeCmd.deserialize(command.operationBytes());
    // Process fixed command
    final var result = commandFixed(slot, value);
    // Only if the current node was the one that the client sent the command to do we complete the future
    Optional.ofNullable(replyToClientFutures.remove(clientMsgUuid))
        .ifPresent(future -> future.complete(result));
  }

  /// This method is called by the client to acquire a lock. During steady state after crash recover it should only be
  /// called on a node that believes it is the leader. The Paxos algorithm ensures safety even if two or more node is
  /// are attempting to lead or of the node suddenly abdicates or crashes.
  ///
  /// This method takes a future and stores it in a map. Only when there is a {@link com.github.trex_paxos.msg.LearningMessage}
  /// sent back will {@link PaxosService#upCall(Long, Command)} be called when the value is fixed in a slot. At that point the future
  /// will be completed and the result should be passed back to the client code which may be in the same JVM or across
  /// a networks.
  ///
  /// IMPORTANT: If you specified host managed transactions in the `TrexNode` constructor then you must ensure that
  /// your make the journal data crash durable after this method return.
  public void processCommand(final CMD value, CompletableFuture<RESULT> future) {
    executor.submit(() -> {
      try {
        final byte[] valueBytes = serdeCmd.serialize(value);
        final var command = new Command(UUIDGenerator.generateUUID().toString(), valueBytes);
        LOGGER.info(() -> "processCommand value=" + value + " clientMsgUuid=" + command.clientMsgUuid());
        replyToClientFutures.put(command.clientMsgUuid(), future);
        createAndSendLeaderMessages(List.of(command));
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Exception processing command: " + e.getMessage(), e);
        future.completeExceptionally(e);
      }
    });
  }

  ///
  public void createAndSendLeaderMessages(List<Command> command) {
    final var messages = engine.nextLeaderBatchOfMessages(command);
    networkOutboundSockets.accept(messages);
  }

  /// This is the actual application logic. It takes the fixed command value and applies it to the lock store.
  ///
  /// @param slot              The slot number that the command value was fixed in. This associates a unique 64bit number with every lock acquisition.
  /// @param fixedCommandValue The client command value that was fixed in the slot.
  protected RESULT commandFixed(Long slot, CMD fixedCommandValue) {
    return host.apply(slot, fixedCommandValue);
  }

  /// This method may be called to get the current role of the node. During a network partition there may be
  /// two or more nodes that have role `LEAD`. Which is why this method returns an estimated role as you cannot
  /// actually know which node is the true leader without running the Paxos algorithm over a value.
  @SuppressWarnings("unused")
  public TrexNode.TrexRole getEstimatedRole() {
    return engine.getRole();
  }
}
