/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.PaxosService;
import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;

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

    Host(LockStore lockStore) {
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
                cmd.expiryTime(),
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
