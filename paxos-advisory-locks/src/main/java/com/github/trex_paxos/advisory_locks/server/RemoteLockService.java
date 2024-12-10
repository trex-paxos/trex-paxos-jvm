package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.Command;
import com.github.trex_paxos.PaxosService;
import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/// The `RemoteLockService` runs the server side logic that runs at each node the fixed sequence of remote
/// procedure calls. It is the host application logic based on fixed values from the Paxos algorithm.
public class RemoteLockService extends PaxosService<LockServerCommandValue, LockServerReturnValue> {
  static final Logger LOGGER = Logger.getLogger("");

  /// The application state.
  private final LockStore lockStore;

  /// This method is not public as only used by the unit tests to verify the lock store state.
  LockStore getLockStore() {
    return lockStore;
  }

  /// @param lockStore The lock store which is our application host logic. Paxos keeps the lock store state
  ///                  at each node in the Paxos cluster identical.
  /// @param engine The Paxos engine that handles timeouts, heartbeats, and message processing. It guards
  ///               a `TrexNode` which runs the Paxos algorithm.
  /// @param networkOutboundSockets An outbound message consumer that will send outbound messages to the other nodes in
  ///                               the Paxos cluster. *_IMPORTANT_* If you specify {@link TrexEngine#hostManagedTransactions} the networkOutboundSockets must make the journal
  ///                               state durable before sending out messages.
  public RemoteLockService(LockStore lockStore,
                           TrexEngine engine,
                           Consumer<List<TrexMessage>> networkOutboundSockets) {
    super(engine, networkOutboundSockets);
    this.lockStore = lockStore;
  }

  /// This the method that does the work. It has to convert the network messages which are RPC requests into
  /// meted calls on the application state. This is where the Paxos algorithm is applied. The result is the
  /// RPC response that will be sent back to the client.
  @Override
  public LockServerReturnValue apply(Long slot, LockServerCommandValue fixedCommandValue) {
    LOGGER.fine(() -> "apply slot=" + slot + " fixedCommandValue=" + fixedCommandValue);
    return switch (fixedCommandValue) {
      case LockServerCommandValue.TryAcquireLock cmd -> new LockServerReturnValue.TryAcquireLockReturn(
          lockStore.tryAcquireLock(
              cmd.lockId(),
              slot,
              cmd.holdDuration()
          )
      );

      case LockServerCommandValue.ReleaseLock cmd -> new LockServerReturnValue.ReleaseLockReturn(
          lockStore.releaseLock(
              cmd.lockId(),
              cmd.stamp()
          )
      );

      case LockServerCommandValue.GetLock cmd -> new LockServerReturnValue.GetLockReturn(
          lockStore.getLock(cmd.lockId())
      );
    };
  }

  /// This is required to deserialize the chosen command bytes into the RPC request object.
  @Override
  public LockServerCommandValue convertCommand(Command command) {
    return LockServerPickle.unpickleCommand(command.operationBytes());
  }

  /// This is required to serialize the RPC response object into bytes that can be sent over the network.
  @Override
  public byte[] pickle(LockServerCommandValue value) {
    return LockServerPickle.pickle(value);
  }
}
