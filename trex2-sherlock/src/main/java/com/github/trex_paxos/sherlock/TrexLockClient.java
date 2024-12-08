package com.github.trex_paxos.sherlock;

import java.time.Duration;
import java.time.Instant;

/// The concept of a distributed lock is a fundamental building block for distributed systems.
/// It is a bad idea to call them locks however, as they are not locks in the traditional JVM
/// sense as per {@link java.util.concurrent.locks.Lock}. They are more like leases.
///
/// {@link LockHandle} is a handle to the acquired lock and a different thread
/// may release the lock than the one that acquired it. This is a crucial concept for distributed
/// locks. The lock is not held in the JVM process. It is held in a remote store updated by different JVMs.
/// We must make network calls to acquire and release the lock. These will likely be different
/// threads.
///
/// Locks must have an ID in order for them to have an identity that spans many JVMs.
/// If we have multiple objects in the same JVM that have the same ID then they are the same logical lock.
/// You must be careful if you attempt to synchronize on the handle object itself as you may be synchronizing on a
/// different objects which would be a fatal mistake.
public interface TrexLockClient {

  /// Attempt to acquire the lease for a specific duration of time. This must always make network calls to find
  /// out of some other JVM has just acquired or released the lock.
  ///
  /// @param id                 The ID of the lock which is what you want to identify whatever the logical lock is guarding.
  /// @param durationToHoldLock The duration after which the lock will expire.
  LockHandle tryLock(String id, Duration durationToHoldLock);

  /// The concept of expiry time is perilous in distributed systems. The time is not the time on the local machine.
  /// The time is the time on the machine that holds the lock. That machine may have clock drift with the local machine.
  /// So the true time that the lock expires may be in the past or future of this current machine.
  /// This method returns the time that the machine that holds the lock thinks will be the Java epoch time in milliseconds
  /// when it will no longer holds the lock. Even if we had perfect clocks if the other JVM has a GC stall it might see
  /// that it has not expired, then have a GC stall, then do work after the lock has expired. All in all this means
  /// that it is extremely perilous to rely on the exact expiry time of a lock in milliseconds in a distributed system.
  /// See {@link #expireTimeWithSafetyGap(LockHandle, Duration)} for a safer way to calculate the expiry time.
  ///
  /// @param lock The lock handle that we want to know the expiry time of.
  long expireTimeUnsafe(LockHandle lock);

  /// Due to clock drift between server hosts and the possibility of GC stalls on the server host it is perilous to rely
  /// on the exact expiry time of a lock in milliseconds in a distributed system. See {@link #expireTimeUnsafe(LockHandle)} for
  /// an explanation of why. This method returns the time that the local machine should think that the lock will expire
  /// after adding on a safety gap. This safety gap should be a few seconds to a few minutes spending on who long the
  /// other JVM might continue working on a given resource after the lock has expired. For example if the lock was to allow
  /// a JVM to some batch work that could take up to five minutes then we might want to add a safety gap of five minutes
  /// before we another JVM can safely assume that the lock has expired.
  Instant expireTimeWithSafetyGap(LockHandle lock, Duration safetyGap);

  boolean releaseLock(LockHandle lock);

}
