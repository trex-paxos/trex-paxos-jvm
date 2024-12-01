## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

This repository contains a Java implementation of the Paxos algorithm as described in Leslie Lamport's 2001
paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf)

This is a work in progress, as more exhaustive tests will be written. At this point, it is not recommended for
production use. A release candidate will be made when the exhaustive tests mentioned in this readme are implemented.

### Introduction

The ambition of this documentation is to:

1. Provide sufficient detail about the invariants described in the original paper to transcribe them into rigorous tests.
2. Clarify that the approach taken in this implementation is based on a careful and thorough reading of the original
   papers, watching Lamport's videos, and careful research about other implementations.
3. Provide sufficient detail around the "learning" messages used by this implementation to understand that they are minimal and do not harm correctness.
4. Provide enough documentation so that someone can carefully study the code, the tests, and the papers to verify this implementation with far less overall effort than it would take them to write any equivalent implementation.
5. Explicitly explain the design decisions in this implementation. 

As of today, the proceeding list is aspirational. When the exhaustive tests are written, I will invite peer review and
possibly offer a nominal bug bounty (which would be a folly I would surely come to regret instantly).

### Cluster Replication With Paxos

To replicate the state of any service, we need to apply the same stream of commands to each server in the same order.
The [paper](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) states (p. 8):

> A simple way to implement a distributed system is as a collection of clients that issue commands to a central server. The server can be described as a deterministic state machine that performs client commands in some sequence. The state machine has a current state; it performs a step by taking as input a command and producing an output and a new state.

For example, in a key-value store, commands might be `put(k,v)`, `get(k)` or `remove(k)` operations. These commands form
the "values" that must be applied consistently at each server in the cluster.

The [paper](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) explicitly states that Paxos has a leader (p. 6):

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

This means command values are forwarded to the leader, and the leader assigns the order of the command values.

A common misconception is failing to recognize that Paxos is inherently Multi-Paxos.
The [paper](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) states (p. 10):

> A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm. Using the same proposal number for all instances, it can do this by sending a single reasonably short message to the other servers.

This enables the algorithm to enter a steady state of streaming only `accept` messages until a leader crashes or becomes
network-isolated. Only then are `prepare` messages necessary for simultaneous leader election and crash recovery.

The description below refers to server processes as "nodes" within a cluster. This helps to disambiguate the code
running the algorithm from the physical server or host process. This repository provides a core library with a node
class `TrexNode` solely responsible for running the core Paxos algorithm.

### The Paxos Algorithm

This description will explain it in the following order:

* First, explain that promises apply to both core message types.
* Second, explain the steady state of the algorithm, which uses only `accept` messages.
* Third, explain how nodes may efficiently learn which values have been fixed.
* Fourth, explain the leader take-over protocol, which is the most complex step that uses both `prepare` and `accept` messages.
* Fifth, explain the durable state requirements.
* Sixth, define the invariants of this implementation.
* Seventh, explain this implementation's design choices.
* Eighth, provide a footnote on leader duels.

### First: Promises, Promises

The core algorithm uses only two protocol messages, `prepare(_,N)` and `accept(_,N,_))` where `N` is called a ballot
number or a proposal number. Nodes promise to reject protocol messages associated with a lower number than the last `N`
they did not reject. This means each node stores the highest `N` it has previously acknowledged.

If you have studied Paxos before, you may be surprised to learn that nodes must make promises to both message types.
Lamport talks about this fact in a video lecture. He describes it as the only ambiguity in his 2001 paper Paxos Made
Simple. He explains that this detail is included in his formal TLA+ specification of the Paxos Algorithm.

The number `N` must be unique to a given node for the algorithm to be correct. Lamport writes (p. 8):

> Different proposers choose their numbers from disjoint sets of numbers, so two different proposers never issue a proposal with the same number.

This is achieved by encoding the node identifier in each `N`s lower bits. 
This library uses a record with a signature similar to this: 

```java
public record BallotNumber(int counter, byte nodeIdentifier) implements Comparable<BallotNumber> {
}
```

The `compareTo` method treats the four-byte counter as having the most significant bits and the
single-byte `nodeIndentifier` as having the least significant bits. The cluster operator must ensure they assign unique
`nodeIdentifier` values to every node added to the cluster.

In this implementation, nodes never recycle their numbers. They increment their counter each time they attempt to lead. 
This avoids the need to retransmit values when fixing slots, as explained below.

### Second: Steady State Galloping

The objective is to fix the same command value `V` into the same command log stream index `S`, known as a log slot, at each node in the cluster. When the network is healthy, and servers have undertaken crash recovery, an uncontested leader sends a stream of commands using `accept(S,N,V)` messages where:

* `S` is a log index slot the leader assigns to the command value.
* `N` is a node's unique ballot number. 
* `V` is a command value.

The value `V` is fixed at slot `S` when a mathematical majority of nodes journal the value `V` into their log. No matter
how many leaders attempt to assign a value to the same slot `S`, they will all assign the same `V` using different
unique `N` values. How that works is described below. 

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
As the leader is the first to learn which values are fixed, Lamport calls the leader the “distinguished learner”. 

The leader can send a short `fixed(S,N)` message to inform the other nodes when a value has been fixed. This message can
piggyback at the front of the subsequent outbound `accept` message network packet. Due to lost messaging, a leader may 
learn which slots are fixed out of sequential order. In this implementation leaders only send `fixed` messages in sequential slot order. 

Leaders must always increment their counter to create a fresh `N` each time they attempt to lead. That ensures that each
`fixed(S,N)` refers to a unique `accept(S,N,V)` message. If any node does not have the matching 
`accept(S,N,V)` in its journal, it must request retransmission. This implementation uses a `catchup` message to request the
retransmission of missed `accept` messages. 

This implementation uses code similar to the following to allow nodes other than the leader to learn which values have been fixed:

```java
public record Fixed(
    BallotNumber number,
    long fixedLogIndex) {
}

public record Catchup(long highestFixedIndex) {
}

public record CatchupResponse( List<Accept> catchup ) {}
```

Each node learns which value `V` is fixed into each sequential slot `S`.
Each nide will thrn up-call the command value `V` to the host application. 

### Fourth: The Leader Takeover Protocol

On leader election (p. 7):

> A reliable algorithm for electing a proposer must use either randomness or realtime — for example, by using timeouts. However, safety is ensured regardless of the success or failure of the election.

This implementation leader elections using random timeouts.
It allowe you to use a different mechanism by isolating the pure algorithm logic 
into the `TrexNode` class. The timeout logic is isolated into the `TrexEngine` class. 
When a node times out it attempts to run the leader takeover protocol:

1. The new leader sends `prepare(N,S)` for all slots any prior leader has attempted to fix
2. For each slot nodes respond with promise messages containing any unfixed `{S,N,V}` tuples else only `{S,N}` when it has no value in that slot. 
3. For each slot the leader selects the `V` that was associated with the highest `N` value from a majority of responses. If there was no value known at that slot by a majority then the new leader can safely use its own command value `V` at that slot.
4. For each slot the leader sends fresh `accept(S,N,V)` messages with chosen command `V` using its own `N` for each slot

If you have been previously studied Paxos that description says that the leader takeover protocol is to run the 
full algorithm for many slots. The only question is what is the range of slots that we need to recover. This is the range of slots tht any previous leader has attempted to fix.
A value is only fixed at a slot if a majority of nodes have journaled it. Clearly, one node in any majority must have 
journaled the value. We can ask a majority of nodes and use the max value. 

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
when a leader learns that a slot is fixed in sequence, it will issue a `fixed(S,N)`. Again, if it gets a majority negative 
acknowledgement for any slot, it abdicates. 

In this implementation, a new leader first issues a `prepare` for the slot immediately after the highest slot it knows was fixed.
The new leader instantaneously sends the response message to itself and instantaneously 
responds, which is a message that includes its own `highestAccepted`. When it gets a majority 
positive response, it computes `max(highestAccepted)` to know all the slots it must recover.
It then streams `prepare` messages for the full range of slots. 

Intuitively, we can think of the first message as a leader election. Hence, we call
`N` a ballot number, and we consider the responses to be votes.
In a three-node cluster, a leader only needs to exchange one 
message to be elected. It immediately issues small prepare 
messages for the full range of slots. These may be batched into a single 
network packet. We can recover a range of slots in parallel  
without making a network roundtrip per slot. In essence, the new leader is 
asking nodes to retransmit what they know about past `accept` messages.

## Fifth, Durable State Requirements

The state of each node is similar to the following model:

```java
public record Progress( BallotNumber highestPromised,
                        long fixedIndex) {
}

public interface Journal {
   void saveProgress(Progress progress);
   Progress loadProgress();
   void write(long logIndex, Accept accept);
   Accept read(long logIndex);
   void sync();
   long highestAcceptedSlot();
}
```

The progress of each node is its highest promised `N` and its highest fixed slot `S`. 
Journal writes must be crash-proof (disk flush or equivalent). 
The journal's `sync()` method must first flush any commands into their slots and only then flush the `progress`.
The method `long highestAcceptedSlot()` is required to run the leader takeover protocol. 

The journal is a single interface. It could be implemented as PostgreSQL running on the same physical server or using an embedded relational database such as H2. The progress record would be a small table with only one row.
The accept table would use the log index as the primary key.
You could create a maintenance cronjob that computes the minimum `fixedIndex` of the progress across all nodes. You can then 
safely delete all rows from the accept table logic index below that minimum value. 

You do not have to use a relational database to implement the journal interface. Different storage engines can be used for each of the `accept` messages, the values, and the progress record. 
All that matters is that the values must be made crash-durable before the accept log is made crash-durable, 
before the progress is made crash durable. 

## Sixth, The invariants

This implementation enforces the following invariants at each node:

1. The fixed index increases sequentially (therefore, the up-call of `{S,V}` tuples must be sequential).
2. The promise number only increases (yet it may jump forward).
   3The promised ballot number can only change when processing a `prepare` or `accept` message.
   4The fixed index can only change when a leader sees a majority `AcceptReponse` message, a follower node sees a
   `Fixed` message or any node learns about a fixed message due to a `CatchupResponse` message.

The core of this implementation that ensures safety is written as inequalities comparing integer types. When we unit test the `TrexNode` class: 

* It can only see messages that are less than, greater than, or equal to its promise.
* It can only see messages from another node with a node identifier that is less than, greater than, or equal to its own.
* It can only see messages with a fixed slot index that are less than, greater than, or equal to its own.
* The journal at any slot can have only no value, the no-operation value, or a client command value.
* The journal can either be continuous, have gaps, or not have reached a specific index when that index is learnt to be fixed.  

If we had ten such things to brute force test, each with only three permutations, we would have 3^10=59,049 test scenarios to brute force. 

TO BE CONTINUED

### Seventh, Design Choices

The description above explains that the happy path galloping state is optimal. Therefore, this implementation makes the following design decision:

> Do not optimise the happy path for lost messages. Have nodes request retransmission.

In my day job, I have had to read carefully and occasionally document n what happened during outages of complex distributed systems.  These experiences led me to adopt the “let it crash” philosophy:

> Do not attempt to use any error recovery logic to deal with IO problems.
 
The safest approach is to follow a ”let it crash” philosophy and have something monitor and kill processes 
that get “stuck” due to infrastructure outages.

If this implementation sees something unexpected outside of steady state, the `TrexNode` will first  
clear any leadership state and return to being a follower. That may lead to the leader's recovery protocol 
being rerun to ensure correctness due to lost messages. 

If this implementation hits IOExceptions or anything else in the critical code, it marks itself 
as “crashed” and refuses to do anything else. You need to kill the process to reinitialise everything from the 
durable state. 

My final observation is that it is hard-won over a quarter of a century of working on distributed systems. The moment we know 
a bug exists. We find it within an hour and patch it. Yet recovery in production and rolling out the patch 
can take multiple days. No amount 
of code review or developer-written tests ever finds all of the bugs. We should aim to write brutally simple code with less surface for bugs to hide. We should aim to do brute-force style testing on mission-critical code.
This leads to this design choice:

> Keep it as simple as possible and attempt a brute-force search for bugs. 

Only the author's mistakes in the specification and implementation of the invariants and their tests will remain. 
The benefit of open source is that, with enough eyes, any errors can be found and fixed.

### Eighth, Leader Duels

The [paper](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) states (p. 7):

> It’s easy to construct a scenario in which two proposers each keep issuing
> a sequence of proposals with increasing numbers, none of which are ever
> chosen.

One pathological scenario exists for a stable network where nodes repeated timeout in an unlucky order such:

* The first node to timeout has the lowest node identifier.
* The second node to timeouts does so before the first node can fix a value into the next slot.

Yet there is also the other scenario where the first node to timeout has the highest node identifier. Then the second
node to timeout does not interrupt. If you were to set things up so that you had a 50% probability of two nodes
timing out within the time it takes them to complete a full slot recovery you have great odds.

Every timeout you have two chances of success; that the first node to timeout has the highest node identifier, and if
not
that the lower node is not interrupted before it can complete a full cycle. The odds of success each attempt to elect
a leader are 75%, 94%, 99%, ..

This implementation separates the core algorithm into TrexNode and the timeout logic into TrexEngine. It will allow you
to use your own node failure detection or election mechanism if you do not like those odds.

See the wiki for a more detailed explanation of this topic.

## Development Setup

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
- [ ] Add optionality so that randomised timeouts can be replaced by some other leader failover detection and voting
  mechanism (e.g. JGroups).

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
