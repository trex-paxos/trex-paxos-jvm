## TrexLock: Distributed Advisory Locks As Distributed Leases Using An Embedded Paxos Library

### TL;DR

This codebase:

* Implements distributed advisory locks (as distributed leases) using the Trex2 Paxos library.
* Provides only a minimal subset of features compared
  to [The Chubby lock service](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf).
* Provides a simple server where you run a cluster of where they all keep exactly in sync by using only using the Paxos
  algorithm.
* Is small enough to be embeddable. You could embed the lock server into your actual application JVMs.
* Uses the H2 MVStore as the default storage backend to be both the paxos journal and to hold the lease state.
* Allows you to use an external database, or kv-store, or document store if you so wish.
* Allows you to run a cluster of three or five servers. Even numbers are supported (including just two) but are not
  recommended.

### What Is An Advisory Lock Server?

Significant elements of the following are paraphrasing the work of Mick Burrows in the paper "The Chubby lock service
for loosely-coupled distributed systems". Advisory locks are locks that only prevent other attempts to acquire the same
lock, but do not automatically prevent
access to the protected resource:

> Like the mutexes
> known to most programmers, locks are advisory. That
> is, they conflict only with other attempts to acquire the
> same lock: holding a lock called R neither is necessary
> to access the resource R, nor prevents other clients from doing
> so. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

You can use advisory locks for things like:

* Coordinating access to shared resources in distributed systems
* Managing concurrent background processing tasks
* Preventing duplicate execution of scheduled tasks
* Coordinating multi-node task distribution
* Tracking many servers or processes (or pods) coming and going over time.
* Tracking which server is a leader or designated manager of an area of work.

> For example, an application might use a lock to elect a primary, which would then handle all access to that data for a
> considerable time, perhaps hours or
> days. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

An advisory locking service aims at providing coarse-grained locks visible to a large number of clients. For strong
consistency and high availability they require messages to be exchange with timeouts that are measured in tens of
seconds or minutes. In contrast, fine-grained locks are typically held for avery short durations, often a few seconds
or less. Fine-grained locks are used to synchronize highly frequent operations, such as individual transactions or
small fast updates within a system. Because of their short duration and high frequency of acquisition and release,
fine-grained locks impose a significant load on a server.

You can use the coarse-grained lock to allocate which processes or sets of serves are in charge of your own application
specific fine-grained locks:

> Fortunately, it is straightforward for clients to implement their own fine-grained locks tailored to their
> application.
> An application might partition its locks into groups and use ... coarse-grained locks to allocate these lock groups
> to application-specific lock
> servers. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

Which is just saying you can use a three node lock-sever cluster to designate which of your application servers will be
your running fine-grained, very short-lived and very high throughput locks.

It is crucial concept for advisory locking service that different threads or even different processes can take and
release advisory locks. The advisory lock is not a resource managed in the JVM process.

In any large distributed system the processes that use a locking service will themselves crash. Typically, timeouts of
minutes are used on advisory locks. The lock service can fail over in seconds without causing critical harm to the over
health of the distributed system it supports. Yet it would be highly resilient, and it should automatically fail over
ideally in a few short seconds.

This implementation is a simple lock service that uses the Paxos algorithm to ensure that all servers in a Paxos cluster
are in sync. It only uses the Multi-Paxos Algorithm to stream commands such that in a three node cluster the leader only
needs to hear back from one other node to be able to process a client lock RPC a command. There is no leader election
other than timeouts and running `prepare` messages. After a short crash recovery time fo using `prepare` messages to
resolved any unfixed state a new leader will gallop only issuing `accept` messages. Buffering of the small commands
allows for very high throughput.

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

