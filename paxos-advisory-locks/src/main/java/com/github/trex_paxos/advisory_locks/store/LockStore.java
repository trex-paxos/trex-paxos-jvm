package com.github.trex_paxos.advisory_locks.store;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

import static java.util.Optional.ofNullable;

/// This is the actual store that will be updated by replicating RPC calls against the lock server
/// within a Paxos cluster It uses an embedded MVStore as the backing datastore.
public class LockStore implements AutoCloseable {
  private final MVStore store;
  private final MVMap<String, LockEntry> locks;
  private final MVMap<Long, String> expiryIndex; // Secondary map for expiry tracking
  private final Thread cleanupThread;
  private long estimatedMaxAllocations = 0; // Tracks added lock size
  private static final long MEMORY_THRESHOLD = 1024 * 1024; // Example threshold (1 MB)

  private volatile boolean closed = false;

  public LockStore(MVStore store) {
    this.store = store;
    this.locks = store.openMap("com.github.trex_paxos.advisory_locks.store#locks");
    this.expiryIndex = store.openMap("com.github.trex_paxos.advisory_locks.store#expiryIndex");
    this.cleanupThread = Thread.startVirtualThread(() -> {
      do {
        cleanupExpiredLocks();
        // wait until an estimated threshold
        LockSupport.park();
      } while (!closed);
    });
  }

  /// Try to acquire or reacquire an advisory lock identified by the given lock ID and stamp for a given duration.
  /// This is the basic none blocking lock acquisition method. We can get the lock if:
  ///
  /// 1. The lock does not exist
  /// 2. The lock exists but is expired.
  /// 2. The lock exists yet stamp is same. This makes it a reentrant advisory lock or a lease extension.
  ///
  /// @param lockId The ID of the lock to either create or acquire.
  /// @param holdDuration The duration for which the lock should be held.
  /// @param stamp This is a unique identifier for the lock acquisition attempt.
  public Optional<LockEntry> tryAcquireLock(String lockId, Duration holdDuration, long stamp) {
    // Check if a lock with the given lockId exists and can be replaced
    final var otherLock = Optional.ofNullable(locks.get(lockId))
        .filter(existingLock ->
            !isExpired(existingLock) && existingLock.stamp() != stamp
        );

    // Return empty if an existing valid lock is found; otherwise, create a new one
    return otherLock.isPresent() ? Optional.empty() : Optional.of(createNewLock(lockId, holdDuration, stamp));
  }

  private LockEntry createNewLock(String lockId, Duration holdDuration, long stamp) {
    // Calculate expiry time
    final var expiryTime = Instant.now().plus(holdDuration);

    // Create a new lock entry
    final var newLock = new LockEntry(
        lockId,
        stamp,
        expiryTime,
        System.currentTimeMillis()
    );

    // Add the new lock to primary map and expiry index
    locks.put(lockId, newLock);
    expiryIndex.put(expiryTime.toEpochMilli(), lockId);

    // Increment allocations for the new lock
    incrementAllocations(newLock);

    return newLock;
  }

  /// Releases an advisory lock identified by the given lock ID and stamp.
  ///
  /// This method attempts to release the lock associated with the specified `lockId`
  /// and `stamp`. The release will only succeed if the lock exists and the provided
  /// `stamp` matches the one stored for the lock. If successful, the lock is removed
  /// from both the primary lock map and the expiry index.
  ///
  /// @param lockId The unique identifier of the lock to be released. Must not be null.
  /// @param stamp  The unique stamp associated with the lock acquisition attempt.
  ///               This ensures that only the correct owner can release the lock.
  /// @return true if the lock was successfully released; false if no matching lock
  ///         exists or if the provided stamp does not match.
  public boolean releaseLock(@NotNull String lockId, long stamp) {
    return Optional.ofNullable(locks.get(lockId))
        .filter(existingLock -> existingLock.stamp() == stamp) // Check ownership
        .map(_ -> {
          locks.remove(lockId); // Remove the lock
          return true;          // Indicate success
        })
        .orElse(false); // Return false if no matching lock was found
  }

  public Optional<LockEntry> getLock(String lockId) {
    return ofNullable(locks.get(lockId));
  }

  private boolean isExpired(LockEntry lock) {
    return lock.expiryTime().isBefore(Instant.now());
  }

  @Override
  public void close() {
    closed = true;
    cleanupThread.interrupt();
    // this will commit any pending changes
    store.close();
  }

  public record LockEntry(
      String lockId,
      long stamp,
      Instant expiryTime,
      long acquiredTimeMillis
  ) {
  }

  private void incrementAllocations(LockEntry entry) {
    estimatedMaxAllocations += estimateMemoryForLock(entry);
    if (estimatedMaxAllocations > MEMORY_THRESHOLD) {
      LockSupport.unpark(cleanupThread); // Signal the cleanup thread
    }
  }

  private void cleanupExpiredLocks() {
    long now = System.currentTimeMillis();
    var cursor = expiryIndex.cursor(null);

    while (cursor.hasNext()) {
      Long expiryTime = cursor.next();
      if (expiryTime >= now) break;

      String lockId = cursor.getValue();
      locks.remove(lockId);
      cursor.remove(); // Remove from expiryIndex
    }

    estimatedMaxAllocations = 0; // Reset the allocation counter after cleanup
  }

  private long estimateMemoryForLock(LockEntry entry) {
    int stringSize = entry.lockId().length() * 2; // Each char ~2 bytes
    return stringSize + 16 + 8 + 8; // String + Instant + long stamp + acquiredTimeMillis
  }
}
