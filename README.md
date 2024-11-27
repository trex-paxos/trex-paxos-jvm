## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

This is a work in progress, as more exhaustive tests will be written. At this point, it is not recommended for production use. A release candidate will be made when the exhaustive tests mentioned in this readme are implemented. 

### Introduction

This library implements Lamport's Paxos protocol for cluster replication, as described in Lamport's 2001 paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf). While distributed systems are inherently complex, the core Paxos algorithm is mechanically straightforward when adequately understood. The algorithm and this implementation can achieve consistency across a cluster of nodes using the mathematical minimum number of message exchanges. This is achieved without the need for external leader election services. The net result is an embeddable strong consistency library that can replicate any application state across a cluster of servers. 

The description below explains the algorithm's invariants and the message protocol sufficiently to verify that this implementation is sound. The ambition of this documentation is to: 

1. Provide sufficient detail about the invariants described in the original paper to transcribe them into rigorous tests.
2. Clarify that the approach taken in this implementation is based on a careful and thorough reading of the original papers. 
3. Provide sufficient detail around the "learning" messages used by this implementation to understand that they are minimal and do not harm correctness.
4. Provide sufficient detail to write brute force tests that cover the entire library of messages and all invariants of this implementation.
5. Provide enough documentation so that someone can carefully study the code, the tests, and the papers to verify that they can trust this implementation with far less overall effort than it would take them to write any equivalence implementation.
6. Clarify that writing distributed services is complex and how using this library can help. 

As of today, the proceeding list is aspirational. When the exhaustive tests are written, I will invite peer review and possibly offer a nominal bug bounty (which would be a folly I would surely come to regret). 

### Cluster Replication With Paxos for the Java Virtual Machine

The full long-form essay version is at the wiki post [Cluster Replication With Paxos for the Java Virtual Machine](https://github.com/trex-paxos/trex-paxos-jvm/wiki) for the full description of this implementation of [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf). If you need clarification on something written here and you want a long explanation, refer to that extended essay version. 

A common misconception is failing to recognize that Paxos is inherently Multi-Paxos. As Lamport states in "Paxos Made Simple" (p. 10):

> A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm. Using the same proposal number for all instances, it can do this by sending a single reasonably short message to the other servers.

This enables efficient streaming of only `accept` messages until a leader crashes or becomes network isolated. 

To replicate the state of any server we simply need to apply the same stream of commands at each server in the same order. The paper states (p. 8):

> A simple way to implement a distributed system is as a collection of clients that issue commands to a central server. The server can be described as a deterministic state machine that performs client commands in some sequence. The state machine has a current state; it performs a step by taking as input a command and producing an output and a new state.

For example, in a key-value store, commands might be `put(k,v)`, `get(k)` or `remove(k)` operations. These commands form the "values" that must be applied consistently at each node in the cluster. 

The challenge is ensuring the consistency of the command stream across all servers when messages are lost and servers crash. 

### The Paxos Protocol 

We must fix the same commands into the same command log stream index, known as a log slot, at each server: 

* Commands (aka values) are replicated as byte arrays (supporting JSON, protobuf, Avro)
* Each command is assigned to a sequential log index (slot)
* Leaders propose commands using `accept(S,N,V)` where:
  * S: logical log index/slot
  * N: proposal number unique to a leader
  * V: proposed command/value
* Upon a majority positive response to any `accept` message the value held in that the slot is fixed by the algorithm and will not change. 

Whenever a node receives a message with a higher `N` that it replies to positively, it has made a promise to reject any further messages that have a lower `N`. 

As Lamport specifies the proposal number on (p. 8):

> Different proposers choose their numbers from disjoint sets of numbers, so two different proposers never issue a proposal with the same number."

This is achieved by encoding the node identifier in each `N`s lower bits.

Lamport explicitly defines leadership (p. 6):

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

On leader election (p. 7):

> A reliable algorithm for electing a proposer must use either randomness or realtime — for example, by using timeouts. However, safety is ensured regardless of the success or failure of the election.

The novelty of Paxos was that it did not require real-time clocks. This implementation uses random timeouts: 

1. Leader sends `prepare(N,S)` for slots not known to be fixed
2. Nodes respond with promise messages containing any uncommitted `{N,V}` pairs at that slot `S`
3. Leader selects the `V` that was with the highest `N` value from a majority of responses
4. Leader sends fresh `accept(S,N,V)` messages with selected commands using its own `N`

Again, whenever a node receives a message with a higher `N` that it replies to positively, it has made a promise to reject any further messages that have a lower `N`. 

Whatever value at a given slot is held by a majority of nodes it can not not change value. The leader listens to the response messages of followers and learns which value has been fixed. It can then send a short `commit(S,N)` message to let the other nodes know. This is not covered in the original papers but is a standard optimisation known as a Paxos "phase 3" message. We do not need to send it in a separate network packet. It can piggyback at the front of the network packet of the next `accept` message. 

When each node learns that slot `s` is fixed, it will  up-call the command value `V` to the host application. This will be an application-specific callback that can do whatever the host application desires. The point is that every node will up-call the same command values in the same order. Nodes will ignore any messages that are less than or equal to the highest slot index it has learnt has been fixed. 

This library uses messages similar to the following code to allow nodes to learn about which commands are fixed in slots:

```java
public record Prepare( long logIndex,
                       BallotNumber number ) {}

public record PrepareResponse(
    long logIndex,
    BallotNumber number,
    boolean vote,
    Optional<Accept> highestUncommitted ) {}

public record Accept( long logIndex,
                      BallotNumber number,
                      Command command ) {}

public record AcceptResponse(
    long logIndex,
    BallotNumber number,
    boolean vote ){}

public record BallotNumber(int counter, byte nodeIdentifier) {}

public record Command( String id,
                       byte[] operationBytes){}
```

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

The progress of each node is it's highest promised `N` and it's highest committed slot `S`. The command values are journaled to a given slot index. Journal writes must be crash-proof (disk flush or equivalent). The `sycn()` method of the journal must first flush any commands into their slots and only then flush the `progress`. 

The final question is what happens when nodes have missed messages. They can request retransmission using a `catchup` message. This implementation uses messages similar to the following code to learn which commands are fixed into which slots:

```java
public record Commit(
    BallotNumber number,
    long committedLogIndex ) {}

public record Catchup(long highestCommitedIndex ) {}

public record CatchupResponse( List<Command> catchup ) {}
```

It is important to note that additional properties are in the real code. These are used to pass information between nodes. For example; a node trying to lead may not know the full range of slots that a previous leader has possibly fixed a value. One node in any majority does know. So in the `PrepareReponse` messages we add a `higestAcceptedIndex` property. A node that is attempting to load will then learn the maximum range of slows that it must probe with `prepare` messages. 

The above algorithm has a small mechanical footprint. It is a set of rules that imply a handful of inequality checks. The entire state space of a distributed system is hard to reason about and test. There are a near-infinite number of messages that could be sent. Yet the set of messages that may alter the progress of a node or cause it to commit is pretty small. This implies we can use a brute-force property testing framework to validate that the code correctly implements the protocol documented in the paper. 

There is only one known gotcha that is not clear from the paper. It is evident in Lamport's TLA+ definition of the algorithm. You must increment the promise when seeing either a higher `promise` message or a higher `accept` message. Any given `prepare(S,N)` applies to all higher slots. Due to lost messages, a node might have never seen the original `prepare` message. An `accept` containing a higher `N` proves the original `prepare` message was lost. The node must make a promise to the lost message. Only then is it allowed to process the `accept` message. 

See the wiki for more details. 

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
