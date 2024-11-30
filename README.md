## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

This is a work in progress, as more exhaustive tests will be written. At this point, it is not recommended for
production use. A release candidate will be made when the exhaustive tests mentioned in this readme are implemented.

### Introduction

This library implements Lamport's Paxos protocol for cluster replication, as described in Lamport's 2001
paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf). While distributed systems are
inherently complex, the core Paxos algorithm is mechanically straightforward when adequately understood. It is simple
enough that a well-written implementation can define the invariants as properties and then use a brute-force approach to
search for bugs.

The description below explains the algorithm's invariants and the message protocol sufficiently to verify that this
implementation is sound. The ambition of this documentation is to:

1. Provide sufficient detail about the invariants described in the original paper to transcribe them into rigorous tests.
2. Clarify that the approach taken in this implementation is based on a careful and thorough reading of the original
   papers.
3. Provide sufficient detail around the "learning" messages used by this implementation to understand that they are minimal and do not harm correctness.
4. Provide enough documentation so that someone can carefully study the code, the tests, and the papers to verify this implementation with far less overall effort than it would take them to write any equivalent implementation.
5. Explicity explains the design decisions in this implementation. 

As of today, the proceeding list is aspirational. When the exhaustive tests are written, I will invite peer review and
possibly offer a nominal bug bounty (which would be a folly I would surely come to instantly regret).

### Cluster Replication With Paxos

To replicate the state of any service, we need to apply the same stream of commands to each server in the same order. The paper states (p. 8):

> A simple way to implement a distributed system is as a collection of clients that issue commands to a central server. The server can be described as a deterministic state machine that performs client commands in some sequence. The state machine has a current state; it performs a step by taking as input a command and producing an output and a new state.

For example, in a key-value store, commands might be `put(k,v)`, `get(k)` or `remove(k)` operations. These commands form
the "values" that must be applied consistently at each server in the cluster.

Lamport explicitly states that Paxos has a leader (p. 6):

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

This means command values are forwarded to the leader, and the leader assigns the order of the command values.

A common misconception is failing to recognize that Paxos is inherently Multi-Paxos. As Lamport states in "Paxos Made Simple" (p. 10):

> A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm. Using the same proposal number for all instances, it can do this by sending a single reasonably short message to the other servers.

This enables the algorithm to enter a steady state of streaming only `accept` messages until a leader crashes or becomes
network-isolated. Only then are `prepare` messages necessary for simultaneous leader election and crash recovery.

The description below refers to server processes as "nodes" within a cluster. This helps to disambiguate the code
running the algorithm from the physical server or host process. This repository provides a core library with a node
class `TrexNode` that is solely responsible for running the core Paxos algorithm.

### The Paxos Algorithm

It is a pedagogical blunder to introduce the Paxos algorithm to engineers in the order you would write a mathematical
proof of it's correctness. This description will explain it in the following order:

* First, explain that promises apply to both core message types.
* Second, explain the steady state of the algorithm, which uses only `accept` messages.
* Third, explain how servers may learn that values have been fixed efficiently.
* Fourth, explain the leader take-over protocol, which is the most complex step that uses both `prepare` and `accept` messages.
* Fifth, explain the durable state requirements.
* Sixth, define the invariants of this implementation.

### First: Promises, Promises

The core algorithm uses only two protocol messages, `prepare(_,N)` and `accept(_,N,_))` where `N` is called a ballot
number or a proposal number. Nodes promise to reject protocol messages associated with a lower number than the last `N`
they did not reject. This means each node stores the highest `N` it has previously acknowledged.

If you have studied Paxos before, you may be surprised to learn that nodes must make promises to both message types.
Lamport talks about this fact in a video lecture. He describes it as the only ambiguity in his 2001 paper Paxos Made
Simple. He explains that this detail is included in his formal TLA+ specification of the Paxos Algorithm.

The number `N` must be unique to a given node for the algorithm to be correct. Lamport writes (p. 8):

> Different proposers choose their numbers from disjoint sets of numbers, so two different proposers never issue a proposal with the same number.

This is achieved by encoding the node identifier in each `N`s lower bits. This library uses the following Java record as
`N`:

```java
public record BallotNumber(int counter, byte nodeIdentifier) implements Comparable<BallotNumber> { ... }
```

In that record class, the `compareTo` method treats the four-byte counter as having the most significant bits and the
single-byte `nodeIndentifier` as having the least significant bits. The cluster operator must ensure they assign unique
`nodeIdentifier` values to every node added to the cluster.

In this implementation nodes never recycle their numbers. They increment their counter each time they attempt to lead. 
This avoids the need to retransmit values when fixing slots which is explained below.

### Second: Steady State Galloping

The objective is to fix the same command value `V` into the same command log stream index `S`, known as a log slot, at each node in the cluster. When the network is healthy and servers have undertaken crash recovery, an uncontested leader sends a stream of commands using `accept(S,N,V)` messages where:

* `S` is a log index slot the leader assigns to the command value.
* `N` is a node's unique ballot number. The reason it is called a ballot number will only become apparent when we
  describe the crash recovery protocol below.
* `V` is a command value.

The value `V` is fixed at slot `S` when a mathematical majority of nodes journal the value `V` into their log. No matter
how many leaders attempt to assign a value to the same slot `S`, they will all assign the same `V` using different
unique `N` values. How that works is covered in a later section.

We can call this steady-state galloping, as things move at top speed using a different stride pattern than when
walking (or trotting). A leader will self-accept and transmit the message to the other two nodes in a three-node
cluster. It only needs one message response to learn that a mathematical majority of nodes in the cluster have accepted
the value. That is the minimum number of message exchanges required to ensure that the value `V` is fixed. Better yet,
our leader can stream `accept` messages continuously without awaiting a response to each message.

This library uses code similar to the following as the `accept` message and its acknowledgement:

```java
public record Command( String uuid,
                       byte[] operationBytes){}

public record BallotNumber(int counter, byte nodeIdentifier) {}

public record Accept( long logIndex,
                      BallotNumber number,
                      Command command ) {}

public record AcceptResponse(
    long logIndex,
    BallotNumber number,
    boolean vote ){}
```

The boolean `vote` implies each node may
respond with either a positive acknowledgement or a negative acknowledgement. 
This implementation includes negative acknowledgements to both `prepare` and `accept` 
messages. When a leader receives a majority negative response, it abdicates. 

### Third: Learning Which Values Are Fixed

Any value `V` journaled into slot `S` by a mathematical majority of nodes will never change. Cloud environments
typically only support point-to-point messaging. This means that `AcceptResponse` messages are only sent to the leader.
As the leader is the first to learn which values are chosen, Lamport calls the leader the “distinguished learner”. 

The leader can send a short `fixed(S,N)` message to inform the other nodes when a value has been fixed. This message can
piggyback at the front of the subsequent outbound `accept` message network packet. Due to lost messaging a leader may 
learn which slots are fixed out of order. This implementation only issues `fixed` messages in sequential log order. 

Leaders must always increment their counter to create a fresh `N` each time they attempt to lead. That ensures that each
`fixed(S,N)` refers to a unique `accept(S,N,V)` message. If another node never received the corresponding
`accept(S,N,V)`, it must request retransmission. This implementation uses a `catchup` message to request the
retransmission of missed `accept` messages. 

This implementation uses code similar to the following to enable nodes to learn which values have been fixed:

```java
public record Fixed(
    BallotNumber number,
    long fixedLogIndex) {
}

public record Catchup(long highestFixedIndex) {
}

public record CatchupResponse( List<Accept> catchup ) {}
```

It is important to note that we can use any possible set of learning messages as long as we do not violate the
algorithm's invariants.

As each node learns the value `V` fixed into each sequential slot `S`. It will up-call the command value `V` to the host
application. This will be an application-specific callback that can do whatever the host application desires. The point
is that every node will up-call the same command values in the same order. 

Most often a stable leader learns that a 
slot is fixed from a majority of positive acknowledgements. This means that in steady state the leader is first to 
up-call to the host application. The followers learn on the next network packet from the leader.

This implementation has the leader periodically heartbeat out its latest `fixed` message every few tens of milliseconds.
This means that in practice follower nodes are kept up to date to within the configurable heartbeat 
interval. 

This implementation sends negative acknowledgements to `prepare` and `accept` messages with `S` equal to or less than the last log index slot known to be
fixed. A leader who receives a majority of negative acknowledgements will abdicate and request retransmission using a `catchup`
message.

### Fourth: The Leader Takeover Protocol

On leader election (p. 7):

> A reliable algorithm for electing a proposer must use either randomness or realtime — for example, by using timeouts. However, safety is ensured regardless of the success or failure of the election.

The novelty of Paxos was that it did not require real-time clocks. This implementation uses random timeouts.
When a node times out it attempts to run the leader takeover protocol:

1. The new leader sends `prepare(N,S)` for all slots any prior leader has attempted to fix
2. For each slot nodes respond with promise messages containing any unfixed `{S,N,V}` tuples else only `{S,N}` when it has no value in that slot. 
3. For each slot the leader selects the `V` that was associated with the highest `N` value from a majority of responses. If there was no value known at that slot by a majority then the new leader can safely use its own command value `V` at that slot.
4. For each slot the leader sends fresh `accept(S,N,V)` messages with chosen command `V` using its own `N` for each slot

If you have been previously studied Paxos that description says that the leader takeover protocol is to run the 
full algorithm for many slots. The only question is what is the range of slots that we need to recover. This is the range of slots tht any previous leader has attempted to fix.
A value is only fixed at a slot if a majority of nodes have journaled it. Clearly one node in any majority must have 
journaled the value. We can simply ask a majority of nodes and use the max value. 

This library uses code similar to the following for the `prepare` message and its acknowledgement:

```java
public record Prepare( long logIndex,
                       BallotNumber number ) {}

public record PrepareResponse(
    long logIndex,
    Optional<Accept> highestUnfixed,
    long highestAccepted,
    boolean vote ) {}
```

We use `highestAccepted` value to learn the full range of slots 
that any past leader has attempted to fix.

Again, whenever a node receives either a `prepare` or `accept` message protocol message with a higher `N` that it
replies to positively, it has promised to reject any further protocol messages with a lower `N`. Again, 
when a leader learns that a slot is fixed in sequence it will issue a `fixed(S,N)`. Again, if it gets a majority negative 
acknowledgment for any slot it abdicates. 

In this implementation a new leader first issues a `prepare` for the slot immediately after the highest slot it knows was fixed.
The new leader instantaneously send the response message to itself and instantaneously 
responds which is a massage that includes it's own `highestAccepted`. When it gets a majority 
positive response it computes `max(highestAccepted)` to know all the slots that it must recover.
It then streams `prepare` messages for the full range of slots. 

Intuitively, we can think of the first message as a leader election. Hence we call
`N` a "ballot number" and we consider the responses to be "votes". 
In a three node cluster a leader only needs to exchange one 
message to be elected. It immediately issues small prepare 
messages for the full range of slots. These may be batched into a single 
network packet. We can recover a range of slots in parallel  
without making a network roundtrip per slot. In essence the new leader is 
asking nodes to retransmit what they know about past `accept` messages.

## Fifth, Durable State Requirements

The state of each node is similar to the following model:

```java
public record Progress( BallotNumber highestPromised,
                        long fixedIndex) {
}

public interface Journal {
   void saveProgress(Progress progress);
   void write(long logIndex, Command command);
   Command read(long logIndex);
   void sync();
   long highestAcceptedSlot();
}
```

The progress of each node is its highest promised `N` and its highest fixed slot `S`. 

The command values are
journaled into a given slot index. Journal writes must be crash-proof (disk flush or equivalent). 
The journal's `sync ()`
method must first flush any commands into their slots and only then flush the `progress`.
The method `long highestAcceptedSlot()` is required to run the leader takeover protocol. 
The journal is a single interface that could easily be implemented in an embedded Java database else
a relational database such as postresql running on the same physical server.
In which case the progress record would be a small table with only one row.
The accept table would be large and you would use the log index as the primary key.
You could gave a maintenance cronjob check the `fixedIndex` across all nodes to then 
delete from the accept tables for slots below that min value. 

Yet you dont gave to use a relation database. It could be entirely possible to use an external storage service to hold the actual values 
and then run the algorithm using the key to that storage service as the command value. It is entirely 
possible to use different storage for the accept messages than values and a third solution to hold the progress record. 
What matters is that the values must be crash durable, before the accept log is crash durable, 
before the progress is crash durable. You can mix and match storage solutions to get an optimal 
set up. 


## Sixth, The invariants

The above algorithm has a small mechanical footprint. It is a set of rules that imply a handful of inequality checks.
The entire state space of a distributed system is hard to reason about and test. There are a near-infinite number of
messages that could be sent. Yet the set of messages that may alter the progress of a node or cause it to up-call is
pretty small. This implies we can use a brute-force property testing framework to validate that the code correctly
implements the protocol documented in the paper.

It is important to note that the examples above are the minimum information that a node may transmit. It is entirely acceptable that messages carry more information. This is not a violation of the algorithm as long as the algorithm's invariants are not violated. Transmitting additional information gives the following benefits:

1. Leaders can learn from additional information added onto messages the maximum range of slots any prior leader has attempted to fix. That allows a new leader to move into the steady state galloping mode.
2. Leaders can learn why they cannot lead, such as using a number much lower than any prior leader or having a lower
   fixed index than another node in the cluster.

It is entirely acceptable to add any information you choose into any message as long as you do not violate the
protocol's invariants. This implementation uses the following invariants which apply to each node in the cluster:

1. The fixed index increases sequentially (hence, the up-call of `{S,V}` tuples must be sequential).
2. The promise number only increases (yet it may jump forward).
3. The promised ballot number can only increase.
4. The promised ballot number can only change when processing a `prepare` or `accept` message.
5. The fixed index can only change when a leader sees a majority `AcceptReponse` message, a follower node sees a
   `Fixed` message, or any node learns about a fixed message due to a `CatchupResponse` message.

There are some other trivial invariants; each node should only issue a response message to the corresponding request
message.

The algorithm uses only inequalities, not absolute values or absolute offsets:

* It works similarly for three, five or seven node clusters. This means that the actual node numbers are immaterial; only whether a node number is less than, equal to, or greater than, the current node number.
* Likewise, it does not matter what the actual value of any specific number is in any specific protocl message. It only matters if it is less than, equal to, or greater than the current promise.
* A slot in the journal of a node may contain either no value or some value.
* Each node can be in only one of three states: a follower node, a leader galloping in the steady state node, or a timed-out node attempting to lead by running the recovery protocol.
* There are only two protocol messages, two protocol response messages, two learning messages, and one message to request retransmission.

That is a relatively small set of test permutations to brute force.

TO BE CONTINUED

### Design Choices

A careful reading of the description above explains that the happy path galloping state is optimal. 
Where many implementations of any protocl go wrong is in trying to apply optimisation that add
complexity and bugs. 

The ultimate sin of a Paxos library is to not make state durable to go faster in stready state. 
It is a violation of the protocol to do that. The proper way to get throughput is to batch commands into a single 
`Accept`. Rather than putting one client command as bytes into the command byte array 
put a list of commands that were bufferred while awaiting the disk writes. 

I implemented a flawed concept of a “fast forward commit” in an early implementation. 
I attempted to optimise for lost messages on the galloping path when the leadership did not change.
I discovered my error by running thousands of randomised simulations with rolling network partions that checked 
for the divergence of the commit logs of a three node cluster.

This experience led me to make the following design decision:

> Do not optimise the happy path for lost messages. Rather have nodes request retransmission. The ensures that 
the exact protocol and its invarants are guaranteed. 

In my day job, I have many times had to carefully read and sometimes write down what happened during outages of complex distributed systems. 
These may experiences led me to adopt the “let it crash” philosophy:

> Do not attempt to have any smart error handling logic deal with major probles. 
The safest approach is ftrn a ”let it crash” philosophy and have something monitor and kill processes 
that get “stuck” due to infrastructure outage.

If this implementation sees something unexpected outside of stready state the `TrexNode` will first simply 
resert any leadership state and return to being a follower. That may lead to the leader recovery protol 
being rerun to ensure correctness due to lost messages. 

If this implementation hits IOExceptions or anything else in the critical code, it maeks itself 
as “crashed” and refuses to do anything else. You need to kill the process to reintialise everything from the 
durable state. 

My final observation is hard won over a quarter of a century of working on distributed systems. The moment we know 
a bug exists. We find it inside of an hour and patch it. Yet recovery in production and rolling out the patch 
can take multiple days. No amount 
of code review or developer written tests ever finds all of the bugs. We shoukd aim to write brutally simple code where there
is simply less surface for bugs to hide. We should aim to do brute-force style testing on mission critical code.
This leads to this design choice:

> Keep it as simple as possible and attempt a brute force search for bugs. 

Then what remains is the authors mistakes in the specification and implementation of the invariants and the tests. 
The benefit of open source is that with enough eyes, those bugs can be found and then fixed inside of an hour. 

### Project Goals

- Implement state replication with The Part-Time Parliament Protocol (aka Multi Paxos) as documented by Leslie Lamport
  in [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf).
- Implement the protocol in a way that is easy to understand and verify.
- Write a test harness that can inject rolling network partitions.
- Write property based tests to exhaustively verify correctness.
- Ship with zero third-party libraries outside the java base packages.
- Run on Java 22+ for Data Oriented Programming.
- Virtual thread friendly on Java 22+.
- Embeddable in other applications.
- Be paranoid about correctness. This implementation will throw an Error when it can no longer guarantee correctness.
  incorrect result.

### Non-Goals

- Demonstrate arbitrary Paxos use cases.
- Backport to Java 11 LTS.

## Development Setup

After cloning the repository, run:

```bash
./setup-hooks.sh
```

# Releases

TBD

## Tentative Roadmap

The list of tasks:

- [x] Implement the Paxos Parliament Protocol for log replication.
- [x] Write a test harness that injects rolling network partitions.
- [ ] Write property based tests to exhaustively verify correctness.
- [ ] Implement a trivial replicated k-v store.
- [ ] Implement cluster membership changes as UPaxos.
- [ ] Add in optionality so that randomised timeouts can be replaced by some other leader failover detection and voting
  mechanism (e.g. JGroups).

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
