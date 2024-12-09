package com.github.trex_paxos.advisory_locks.store;

import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVMap;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class LockStore {
  private final MVStore store;
  private final MVMap<String, LockEntry> locks;

  public LockStore(MVStore store) {
    this.store = store;
    this.locks = store.openMap("com.github.trex_paxos.advisory_locks.store#locks");
  }

  public Optional<LockEntry> tryAcquireLock(String lockId, long stamp, Duration holdDuration) {
    LockEntry existingLock = locks.get(lockId);
    if (existingLock != null && !isExpired(existingLock)) {
      return Optional.empty();
    }

    LockEntry newLock = new LockEntry(
        lockId,
        stamp,
        Instant.now().plus(holdDuration),
        System.currentTimeMillis()
    );

    locks.put(lockId, newLock);
    store.commit();
    return Optional.of(newLock);
  }

  public boolean releaseLock(String lockId, long stamp) {
    LockEntry existingLock = locks.get(lockId);

    if (existingLock != null && existingLock.stamp() == stamp) {
      locks.remove(lockId);
      store.commit();
      return true;
    }
    return false;
  }

  public Optional<LockEntry> getLock(String lockId) {
    return Optional.ofNullable(locks.get(lockId));
  }

  private boolean isExpired(LockEntry lock) {
    return lock.expiryTime().isBefore(Instant.now());
  }

  public record LockEntry(
      String lockId,
      long stamp,
      Instant expiryTime,
      long acquiredTimeMillis
  ) {}
}
