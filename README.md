## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

This is a work in progress, as more exhaustive tests will be written. At this point, it is not recommended for production use. A release candidate will be made when the exhaustive tests mentioned in this readme are implemented. 

### Introduction

This library implements Lamport's Paxos protocol for cluster replication, as described in Lamport's 2001 paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf). While distributed systems are inherently complex, the core Paxos algorithm is mechanically straightforward when adequately understood. It is simple enough that a well-written implementation can define the invariants as properties and then use a brute-force approach to search for bugs. 

The description below explains the algorithm's invariants and the message protocol sufficiently to verify that this implementation is sound. The ambition of this documentation is to: 

1. Provide sufficient detail about the invariants described in the original paper to transcribe them into rigorous tests.
2. Clarify that the approach taken in this implementation is based on a careful and thorough reading of the original papers. 
3. Provide sufficient detail around the "learning" messages used by this implementation to understand that they are minimal and do not harm correctness.
4. Provide enough documentation so that someone can carefully study the code, the tests, and the papers to verify this implementation with far less overall effort than it would take them to write any equivalent implementation.

As of today, the proceeding list is aspirational. When the exhaustive tests are written, I will invite peer review and possibly offer a nominal bug bounty (which would be a folly I would surely come to instantly regret). 

### Cluster Replication With Paxos

To replicate the state of any service, we need to apply the same stream of commands to each server in the same order. The paper states (p. 8):

> A simple way to implement a distributed system is as a collection of clients that issue commands to a central server. The server can be described as a deterministic state machine that performs client commands in some sequence. The state machine has a current state; it performs a step by taking as input a command and producing an output and a new state.

For example, in a key-value store, commands might be `put(k,v)`, `get(k)` or `remove(k)` operations. These commands form the "values" that must be applied consistently at each server in the cluster. 

Lamport explicitly states that Paxos has a leader (p. 6):

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

This means command values are forwarded to the leader, and the leader assigns the order of the command values.

A common misconception is failing to recognize that Paxos is inherently Multi-Paxos. As Lamport states in "Paxos Made Simple" (p. 10):

> A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm. Using the same proposal number for all instances, it can do this by sending a single reasonably short message to the other servers.

This enables the algorithm to enter a steady state of streaming only `accept` messages until a leader crashes or becomes network-isolated. Only then are `prepare` messages necessary for simultaneous leader election and crash recovery. 

The description below refers to server processes as "nodes" within a cluster. This helps to disambiguate the code running the algorithm from the physical server or host process. This repository provides a core library with a node class `TrexNode` that is solely responsible for running the core Paxos algorithm. 

### The Paxos Algorithm

It is a pedagogical blunder to introduce the Paxos algorithm to engineers in the order you would write a mathematical proof of it's correctness. This description will explain it in the following order: 

* First, explain that promises apply to both core message types. 
* Second, explain the steady state of the algorithm, which uses only `accept` messages.
* Third, explain how servers may learn that values have been fixed efficiently.
* Fourth, explain the leader take-over protocol, which is the most complex step that uses both `prepare` and `accept` messages.
* Fifth, define the invariants of this implementation. 

### First: Promises, Promises

The core algorithm uses only two protocol messages, `prepare(_,N,)` and `accept(_,N,_))` where `N` is called a ballot number or a proposal number. Nodes promise to reject protocol messages associated with a lower number than the last `N` they did not reject. This means each node stores the highest `N` it has previously acknowledged.  

If you have studied Paxos before, you may be surprised to learn that nodes must make promises to both message types. Lamport talks about this fact in a video lecture. He describes it as the only ambiguity in his 2001 paper Paxos Made Simple. He explains that this detail is included in his formal TLA+ specification of the Paxos Algorithm. 

The number `N` must be unique to a given node for the algorithm to be correct. Lamport writes (p. 8):

> Different proposers choose their numbers from disjoint sets of numbers, so two different proposers never issue a proposal with the same number.

This is achieved by encoding the node identifier in each `N`s lower bits. This library uses the following Java record as `N`: 

```java
public record BallotNumber(int counter, byte nodeIdentifier) implements Comparable<BallotNumber> { ... }
```

In that record class, the `compareTo` method treats the four-byte counter as having the most significant bits and the single-byte `nodeIndentifier` as having the least significant bits. The cluster operator must ensure they assign unique `nodeIdentifier` values to every node added to the cluster. 

Nodes never recycle their numbers. They increment their counter each time they attempt to lead. 

### Second: Steady State Galloping 

The objective is to fix the same command value `V` into the same command log stream index `S`, known as a log slot, at each node in the cluster. When the network is healthy and servers have undertaken crash recovery, an uncontested leader sends a stream of commands using `accept(S,N,V)` messages where:

* `V` is a command value. 
* `S` is a log index slot the leader assigns to the command value. 
* `N` is a node's unique ballot number. The reason it is called a ballot number will only become apparent when we describe the crash recovery protocol below. 

The value `V` is fixed at slot `S` when a mathematical majority of nodes journal the value `V` into their log. No matter how many leaders attempt to assign a value to the same slot `S`, they will all assign the same `V` using different unique `N` values. How that works is covered in a later section. 

We can call this steady-state galloping, as things move at top speed using a different stride pattern than when walking (or trotting). A leader will self-accept and transmit the message to the other two nodes in a three-node cluster. It only needs one message response to learn that a mathematical majority of nodes in the cluster have accepted the value. That is the minimum number of message exchanges required to ensure that the value `V` is fixed. Better yet, our leader can stream `accept` messages continuously without awaiting a response to each message. 

This library uses code similar to the following as the `accept` message and its acknowledgement: 

```java
public record Command( String id,
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

### Third: Learning Which Values Are Fixed

Any value `V` journaled into slot `S` by a mathematician majority of nodes will never change. When galloping, the leader first learns that a value is fixed from the response messages of followers.  It can then send a short `commit(S,N)` message to inform the other nodes. This is not covered in the original papers but is a standard optimisation known as a Paxos "phase 3" message. We do not need to send it in a separate network packet. It can piggyback at the front of the network packet of the next outbound `accept` message. 

This is why a node must always increment its counter to use a fresh `N` each time it attempts to lead. That ensures that the tuple `{S,N}` referenes a unique `V` so that the value does not need to be retransmitted in the learning message. If another node never received the corresponding `accept(S,N,V)`, it must request retransmission. This implementation uses a `catchup` message to request the retransmission of fixed values. 

This implementation uses code similar to the following as the messages to “teach” which values are fixed at specific slots. These are known as "learning" messages which are optional message types not discussed in the original papers: 


```java
public record Commit(
    BallotNumber number,
    long committedLogIndex ) {}

public record Catchup(long highestCommitedIndex ) {}

public record CatchupResponse( List<Command> catchup ) {}
```

It is important to note that we can use any possible set of learning messages as long as we do not violate the algorithm's invariants. 

When each node learns that the next contiguous slot `s` is fixed, it will up-call the command value `V` to the host application. This will be an application-specific callback that can do whatever the host application desires. The point is that every node will up-call the same command values in the same order. 

Nodes may ignore any messages associated with slots less than or equal to the highest slot index they have learnt to be fixed. Dropping a message is identical to never receiving a message. That does not violate the safety of the Paxos Algorithm, as it ensures safety in the face of lost messages. 

### Fourth: The Leader Takeover Protocol

On leader election (p. 7):

> A reliable algorithm for electing a proposer must use either randomness or realtime — for example, by using timeouts. However, safety is ensured regardless of the success or failure of the election.

The novelty of Paxos was that it did not require real-time clocks. This implementation uses random timeouts: 

1. Any leader sends `prepare(N,S)` for slots not known to be fixed
2. Nodes respond with promise messages containing any uncommitted `{N,V}` pairs at that slot `S`
3. The leader selects the `V` that was with the highest `N` value from a majority of responses
4. the leader sends fresh `accept(S,N,V)` messages with selected commands using its own `N`

Again, whenever a node receives either a `prepare` or `accept` message  protocol message with a higher `N` that it replies to positively, it has promised to reject any further protocol messages with a lower `N`. This library uses code similar to the following for the `prepare` message and its acknowledgement: 

```java
public record Prepare( long logIndex,
                       BallotNumber number ) {}

public record PrepareResponse(
    long logIndex,
    BallotNumber number,
    boolean vote,
    Optional<Accept> highestUncommitted ) {}
```

## Fifth, The invariants

The state of each node is similar to the following model: 

```java
public record Progress( BallotNumber highestPromised,
                        long committedIndex ) {}

public interface Journal {
   void saveProgress(Progress progress);
   void write(long logIndex, Command command);
   Command read(long logIndex);
   void sync();
}
```

The progress of each node is its highest promised `N` and its highest committed slot `S`. The command values are journaled to a given slot index. Journal writes must be crash-proof (disk flush or equivalent). The journal's `sync ()` method must first flush any commands into their slots and only then flush the `progress`. 

The above algorithm has a small mechanical footprint. It is a set of rules that imply a handful of inequality checks. The entire state space of a distributed system is hard to reason about and test. There are a near-infinite number of messages that could be sent. Yet the set of messages that may alter the progress of a node or cause it to commit is pretty small. This implies we can use a brute-force property testing framework to validate that the code correctly implements the protocol documented in the paper. 

TO BE CONTINUED

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
