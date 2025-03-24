## Trex Corfu: Distributed Log

### TL;DR

This codebase:

* Implements a distributed log using the Corfu protocol as described in the paper [CORFU: A distributed shared log](https://dl.acm.org/doi/10.1145/2535930)
* Creates a cluster of nodes where some nodes are dedicated data storage nodes. 
* The remainder of the nodes can have one of two roles: Sequencer or Consistency. 
* The Consistency nodes run the Paxos protocol to have strong consistency for: 
  * Cluster Service: which servers are in the cluster and what is their role. 
  * Layout Service: which maintains the projection map (mapping log ranges to storage units).
  * Sequencer lease: which server is the designated dedicated Sequencer.
* At any time there is only one Sequencer node. It is responsible for handing the next log entry to clients. 
* The layout service is a small strongly consistent database that maps log ranges to storage units.

### What Is An Advisory Lock Server?

The Sequencer lease requires an Advisory Lock Server. Advisory locks are locks that only prevent other attempts to acquire the same
lock, but do not automatically prevent access to the protected resource:

> Like the mutexes
> known to most programmers, locks are advisory. That
> is, they conflict only with other attempts to acquire the
> same lock: holding a lock called R neither is necessary
> to access the resource R, nor prevents other clients from doing
> so. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

You can use advisory locks to have only one server being a primary: 

> For example, an application might use a lock to elect a primary, which would then handle all access to that data for a
> considerable time, perhaps hours or
> days. [Mike Burrows](https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf)

An advisory locking service aims at providing coarse-grained locks visible to a large number of clients. For strong
consistency and high availability they require messages to be exchange with timeouts. 

It is crucial concept for advisory locking service that different threads or even different processes can take and
release advisory locks. The advisory lock is not a resource managed in the JVM process.

This implementation uses the Paxos Made Simple algorithm to ensure that all servers in a Paxos cluster
are in sync. It only uses the Multi-Paxos Algorithm to stream commands such that in a three node cluster the leader only
needs to hear back from one other node to be able to process a client lock RPC a command. There is no leader election
other than timeouts and running `prepare` messages. After a short crash recovery time fo using `prepare` messages to
resolve any unfixed state a new leader will gallop only issuing `accept` messages. Buffering of the small commands
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

