## TrexLock: Distributed Advisory Locks As Distributed Leases Using An Embedded Paxos Library

### TL;DR

This codebase:

* Implements distributed advisory locks as distributed leases using the Trex2 Paxos library.
* Provides a simple client API to acquire and release leases. 
* Provides a simple server where you run a cluster of servers and they all keep exactly in sync using the Paxos algorithm.
* By definition advisory locks do not prevent access to the protected resource. They are more like leases rather than true locks.  
* Uses the H2 MVStore as the default storage backend to be both the paxos journal and to hold the lease state.
* Trex allows you to use a journal that is using your main database, kv-store or document store, where you can managed
  translations yourself. This means you can use your preferred database or kv-store or document store as the backing data store. 
* As this solution runs Paxos you need at least two nodes in the cluster to get it to work but clearly you should use three or more.

### Overview

Advisory locks are locks that only prevent other attempts to acquire the same lock, but do not automatically prevent 
access to the protected resource: 

> Like the mutexes
known to most programmers, locks are advisory. That
is, they conflict only with other attempts to acquire the
same lock: holding a lock called R neither is necessary
to access the resource R, nor prevents other clients from doing so. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

You can use advisory locks for things like: 

* Coordinating access to shared resources in distributed systems2
* Managing concurrent background processing tasks2
* Controlling access to resources stored in memory or external systems3
* Preventing duplicate execution of scheduled tasks2
* Coordinating multi-node task distribution

It is important to have high availability and strong consistency guarantees when trying to acquire advisory locks. This is because the locks are used to

The concept of an advisory lock is a fundamental building block for distributed systems.
It is a bad idea to call them locks in a Java library, as they are not locks in the traditional JVM
sense as per {@link java.util.concurrent.locks.Lock}. They are more like leases. The closest thing in the
JVM world is a {@link java.util.concurrent.locks.StampedLock} where there is a handle to the acquired lock and a
different thread may release the lock than the one that acquired it. 

It is crucial concept for advisory locking service that different threads or even different processes can take and release advisory locks. 
The advisory lock is not a resource managed in the JVM process. It is held in a distributed store accessed by many JVMs.
We must make network calls to acquire and release the advisory lock. These will likely be on different
threads. This means we need a handle to the logical lease that can be passed around to different threads.

In the JVM there is no concept of a lock automatically being released after some time. The JVM lock APIs that use
time units are to timeout on attempting to acquire the lock. They do not release the lock after a certain time.
In contrast, with a distributed advisory advisory, from a practical perspective it is extremely useful that leases do have an
expiry time. It ensures that crashed JVMs do not keep locks forever.

We will always be making network calls to acquire and release locks. Lost network packets means that
the current JVM may we may have actually acquired the advisory lock but the response was lost. Retrying on RuntimeIOException
to find we had the advisory lock already is a good idea. That looks no different to reacquiring a reentrant lock. So
we only supply those. If you want non-reentrant locks semantics then simply use a JVM Non-ReentrantLock while holding the lease.

### Time Marches Backwards

When using JVM locks we do not have to worry about clock drift between servers and JVMs. When using local locks any full
GC stalls that take a small number of milliseconds affect all threads. When working with time across different JVMs we
we must account for both. Cloud providers work amazingly hard to keep the clocks in sync across their data centres. They
do this for their own very good reasons. However, the clocks will drift. JVMs can stall after checking they hold the
lease such that the very next byte code instruction is not executed for tens of milliseconds. There is no amount of
trying to use microsecond clock sync protocols that will solve this problem. We must always allow for a grace period
when working with distributed locks expiry time.

This implementation allows you to get the expiry time as the java epoch time in milliseconds yet names that method
`expireTimeUnsafe()` to highlight the risks. It then provides a method
`Instant expireTimeWithSafetyGap(Duration safetyGap)`
to allow a safety gap. That method deliberately does return a long exactly to highlight that milliseconds precision is
not possible. We can use `Instant::isBefore()` and `Instant::isAfter()` to compare
with `Instant.now()`. You may call `instant.toEpochMilli()` to be able to compare
it with `System.currentTimeMillis()` if required. 

