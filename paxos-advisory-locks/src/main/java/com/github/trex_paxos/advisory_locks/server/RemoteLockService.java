package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.PaxosService;
import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.function.BiFunction;

// TODO remove this 
public class RemoteLockService extends PaxosService<LockServerCommandValue, LockServerReturnValue> {
  static final Logger LOGGER = Logger.getLogger("");

  public RemoteLockService(LockStore lockStore,
                           TrexEngine engine,
                           final Consumer<List<TrexMessage>> networkOutboundSockets
                           ) {
    super(engine, new Host(lockStore), LockServerPickle.serdeCmd, LockServerPickle.serdeResult, networkOutboundSockets);
  }

  // TODO break this out into separate class
  static class Host implements BiFunction<Long, LockServerCommandValue, LockServerReturnValue> {

    Host(LockStore lockStore){
      this.lockStore = lockStore;
    }

    /// The application state.
    private final LockStore lockStore;

    /// This method is not public as only used by the unit tests to verify the lock store state.
    LockStore getLockStore() {
      return lockStore;
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
                cmd.holdDuration(),
                slot
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
}
}
