package com.github.trex_paxos.sherlock;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/// This interface is based on the coditory/sherlock-distributed-lock library interface.
public interface DistributedLock {
  /// Returns the lock id.
  ///
  /// @return the lock id
  String getId();

  /// Tries to acquire the lock for a given duration.
  /// <p/>
  /// If lock is not released manually, it becomes released after expiration time.
  ///
  /// @param duration lock expiration time when release is not executed
  /// @return true if lock is acquired
  boolean acquire(Duration duration);


  /// Tries to release the lock.
  ///
  /// @return true if lock was released by this method invocation. If lock has expired or was
  /// released earlier then false is returned.
  boolean release();

  /// Tries to acquire the lock and releases it after action is executed.
  ///
  /// @param <T>      type emitted when lock is acquired
  /// @param supplier executed when lock is acquired
  /// @return Optional<T> if lock was acquired
  /// @see DistributedLock#acquire(Duration)
  default <T> Optional<T> runLocked(@NotNull Duration duration, @NotNull Supplier<? extends T> supplier) {

    if (acquire(duration)) {
      try {
        T value = supplier.get();
        return Optional.ofNullable(value);
      } finally {
        release();
      }
    }
    return Optional.empty();
  }

  /**
   * Acquire a lock for specific duration and release it after action is executed.
   * <p>
   * This is a helper method that makes sure the lock is released when action finishes successfully
   * or throws an exception.
   *
   * @param duration how much time must pass to release the lock
   * @param runnable to be executed when lock is acquired
   * @return if lock was acquired and the runnable run successfully
   * @see DistributedLock#acquire(Duration)
   */
  default boolean runLocked(@NotNull Duration duration, @NotNull Runnable runnable) {
    if (acquire(duration)) {
      try {
        runnable.run();
      } finally {
        release();
      }
    }
    return false;
  }
}
