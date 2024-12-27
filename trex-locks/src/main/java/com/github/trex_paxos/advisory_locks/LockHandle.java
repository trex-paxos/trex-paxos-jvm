package com.github.trex_paxos.advisory_locks;

import java.time.Instant;

/// Using object to represent a distributed lock that is spanning many servers is inherently a bad idea.
/// It invites confusion over whether the object is actually the lock or just a handle to the lock. It can
/// only actually be a handle to the logical lock as the lock is not held in the JVM process.
/// This means it simply is not like a traditional JVM lock. You cannot synchronize on it.
public record LockHandle(
    /// The ID of the lock. This will be anything that you want to identify whatever the logical lock is guarding.
    String id,
    /// The stamp is similar to a stamp in a {@link StampedLock} in that it is a unique identifier for the lock.
    /// The trick that we are using here is that it is the paxos log index of when this particular lock was acquired.
    /// This means that the number is unique for each lock acquisition and globally consistent across all JVMs.
    long stamp,
    /// Due to clock drift between server hosts and the possibility of GC stalls on the server host it is perilous to rely
    /// on the exact expiry time of a lock in milliseconds in a distributed system. See {@link TrexLockService#expireTimeUnsafe()}
    /// for an explanation of why. This instance includes an application specific safety gap.
    Instant expireTimeWithSafetyGap
) {
}
