package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.util.Optional;

public sealed interface LockServerReturnValue {
  record TryAcquireLockReturn(
      Optional<LockStore.LockEntry> result
  ) implements LockServerReturnValue {}

  record ReleaseLockReturn(
      boolean result
  ) implements LockServerReturnValue {}

  record GetLockReturn(
      Optional<LockStore.LockEntry> result
  ) implements LockServerReturnValue {}
}
