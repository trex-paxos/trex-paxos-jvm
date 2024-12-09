package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.Command;
import com.github.trex_paxos.PaxosServer;
import com.github.trex_paxos.UUIDGenerator;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LockServer extends PaxosServer
{
  private static final Logger LOGGER = Logger.getLogger(LockServer.class.getName());
  private final Supplier<LockServerTrexEngine> engine;
  private final ExecutorService executor;
  private final ConcurrentMap<String, CompletableFuture<LockServerReturnValue>> pendingCommands
      = new ConcurrentHashMap<>();
  private final Consumer<List<TrexMessage>> networkOutboundSockets;
  private final LockStore lockStore;

  public LockServer(LockStore lockStore,
                    Supplier<LockServerTrexEngine> engine,
                    Consumer<List<TrexMessage>> networkOutboundSockets) {
    super(engine, networkOutboundSockets);
    this.engine = engine;
    this.networkOutboundSockets = networkOutboundSockets;
    this.lockStore = lockStore;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  // TODO: this should be pulled up an allow for batching
  public CompletableFuture<LockServerReturnValue> processCommand(final LockServerCommandValue value) {
    CompletableFuture<LockServerReturnValue> future = new CompletableFuture<>();

    executor.submit(() -> {
      try {
        final byte[] valueBytes = LockServerPickle.pickle(value);
        final var command = new Command(UUIDGenerator.generateUUID().toString(), valueBytes);
        LOGGER.info(() -> "processCommand value=" + value + " clientMsgUuid=" + command.clientMsgUuid());

        // Store future in map
        pendingCommands.put(command.clientMsgUuid(), future);

        // Process command through Paxos and send messages
        nextLeaderBatchOfMessages(List.of(command));
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Exception processing command: " + e.getMessage(), e);
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public void upCall(long slot, Command command) {
    // we need the clientMsgUuid to complete the future if and only if this is the node that the client sent the command to
    final String clientMsgUuid = command.clientMsgUuid();
    // unpickle host application command
    final LockServerCommandValue value = LockServerPickle.unpickleCommand(command.operationBytes());
    // Process fixed command
    final LockServerReturnValue result = commandFixed(slot, value);
    // Only if the current node was the one that the client sent the command to do we complete the future
    Optional.ofNullable(pendingCommands.remove(clientMsgUuid))
        .ifPresent(future -> future.complete(result));
  }

  private LockServerReturnValue commandFixed(long slot, LockServerCommandValue value) {
    return switch(value) {
      case LockServerCommandValue.TryAcquireLock cmd ->
          new LockServerReturnValue.TryAcquireLockReturn(
              lockStore.tryAcquireLock(
                  cmd.lockId(),
                  slot,
                  cmd.holdDuration()
              )
          );

      case LockServerCommandValue.ReleaseLock cmd ->
          new LockServerReturnValue.ReleaseLockReturn(
              lockStore.releaseLock(
                  cmd.lockId(),
                  cmd.stamp()
              )
          );

      case LockServerCommandValue.GetLock cmd ->
          new LockServerReturnValue.GetLockReturn(
              lockStore.getLock(cmd.lockId())
          );
    };
  }

  public LockStore getLockStore() {
    return lockStore;
  }
}
