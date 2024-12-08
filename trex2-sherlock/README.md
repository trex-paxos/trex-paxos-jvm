## TrexLock: Distributed Locks As Distributed Leases Using An Embedded Paxos Library

### TL;DR

This codebase:

* Implements distributed locks as distributed leases using the Trex2 Paxos library.
* Provides a simple API to acquire and release leases that focuses on them spanning multiple JVMs.
* To do everything that you might want to do with native JVM locks across multiple JVMs you would simply grab the
  distributed lease and then use a JVM lock.
* Uses the H2 MVStore as the default storage backend to be both the paxos journal and to hold the lease state.
* Trex allows you to use a journal that is using your main database, kv-store or document store, where you can managed
  translations yourself.

### Overview

The concept of a distributed lock is a fundamental building block for distributed systems.
It is a bad idea to call them locks however, as they are not locks in the traditional JVM
sense as per {@link java.util.concurrent.locks.Lock}. They are more like leases. The closest thing in the
JVM world is a {@link java.util.concurrent.locks.StampedLock} where there is a handle to the acquired lock and a
different thread may release the lock than the one that acquired it. This is a crucial concept for distributed
locks. The lock is not a resource managed in the JVM process. It is held in a distributed store accessed by many JVMs.
We must make network calls to acquire and release the lock. These will likely be on different
threads. This means we need a handle to the logical lock that can be passed around to different threads.

In the JVM there is no concept of a lock automatically being released after some time. The JVM lock APIs that use
time units are to timeout on attempting to acquire the lock. They do not release the lock after a certain time.
In contrast, with a distributed lock, from a practical perspective it is extremely useful that leases do have an
expiry time. It ensures that crashed JVMs do not keep locks forever.

Locks must have an ID in order for them to have an identity that spans many JVMs.
If we have multiple objects in the same JVM that have the same ID then they are the same logical lock.
As we must make network calls to acquire and release locks we may as well ensure that we only have a single
object instance of any logical lock in the JVM. The overheads of are minimal compared to the netork calls and
it allows us to synchronise on the lock object itself.

We will always be making network calls to acquire and release locks. Lost network packets means that
the current JVM may we may have actually acquired the lock but the response was lost. Retrying on RuntimeIOException
to find we had the lock already is a good idea. That looks no different to reacquiring a reentrant distributed lock. So
we only supply those.

It is often desirable to use a distributed lock to scope the logical lock to be held by one JVM amongst many.
Then within each JVM you can use a traditional JVM lock to manage of the resources guarded by the distributed
lease. If you want non-reentrant locks semantics then simply use a JVM Non-ReentrantLock.

### Time Marches Backwards

When using JVM locks we do not have to worry about clock drift between servers and JVMs. When using local locks any full
GC stalls that take a small number of milliseconds affect all threads. When working with time across different JVMs we
we must account for both. Cloud providers work amazingly hard to keep the clocks in sync across their data centres. They
do this for their own very good reasons. However, the clocks will drift. JVMs can stall after checking they hold the
lease such that the very next byte code instruction is not executed for tens of milliseconds. There is no amount of
trying to use microsecond clock sync protocols that will solve this problem. We must always allow for a grace period
when
working with distributed locks expiry time.

This implementation allows you to get the expiry time as the java epoch time in milliseconds yet names that method
`expireTimeUnsafe()` to highlight the risks. It then provides a method
`Instant expireTimeWithSafetyGap(Duration safetyGap)`
to allow a safety gap. That method deliberately does return a long exactly to highlight that milliseconds precision is
not possible. We can use `Instant::isBefore()` and `Instant::isAfter()` to compare
with `Instant.now()`. You may call `instant.toEpochMilli()` to be able to compare
it with `System.currentTimeMillis()` if required. 

