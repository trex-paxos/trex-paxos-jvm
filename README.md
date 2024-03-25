
## Trex2: An modern Paxos engine for the JVM

Checkout the [blog posts](https://simbo1905.wordpress.com/2016/01/09/trex-a-paxos-replication-engine/) for a description of this implementation of [Paxos Made Simple](https://courses.cs.washington.edu/courses/cse550/17au/papers/CSE550.paxos-simple.pdf).

This is a work in progress. It will use the latest Java 22+ Data Oriented Programming and Structured Concurrency to build a modular implementation of the Paxos Made Simple algorithm for state replication.

### Goals

 - Have at least one 3rd party verification tool (e.g. `maelstrom`)
 - Demonstrate log replication with Paxos
 - Use minimal (ideally zero) third-party libraries.
 - Keep all LTS Java beyond > Java 21

### Non-Goals

 - Demonstrate arbitrary Paxos use cases. A replicated k-v store is a sufficient demo.
 - Backport to Java 21 LTS. Structured concurrency and Virtual Threads are compelling. 

## Building

TBD

## Java Spinnaker Demo

TBD

TBD a barebones MVF demo

# Releases

TBD

## Tentative Roadmap

The list of tasks: 

 - [x] Port the paxos protocal messages over from the scala version to be records with serialization. 
 - [ ] Port the scala partial functions of the algorithm over to Java 22 as destructuring switch expressions.
 - [ ] Implement `maelstrom` k-v Raft validation as Paxos. 
 - [ ] Implement a trivial replicated stack. 
 - [ ] Implement Implement cluster membership changes as UPaxos. 
 - [ ] Implement Spinnaker k-v store on k8s. 

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
