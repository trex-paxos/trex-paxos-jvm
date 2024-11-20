
## Trex2: Embeddable Paxos Engine 

Checkout the [blog posts](https://simbo1905.wordpress.com/2016/01/09/trex-a-paxos-replication-engine/) for a description of this implementation of [Paxos Made Simple](https://courses.cs.washington.edu/courses/cse550/17au/papers/CSE550.paxos-simple.pdf).

This is a work in progress. It will use the latest Java 23+ Data Oriented Programming and Structured Concurrency to build a modular implementation of the Paxos Made Simple algorithm for state replication.

### Goals

 - Demonstrate log replication with the Partime Parliment Protocol (aka Multi Paxos).
 - Use zero third-party libraries.
 - Target the Java 25 LTS version for any sophisticated demos. 
 - Target the Java 21 LTS version for the core library. 

### Non-Goals

 - Demonstrate arbitrary Paxos use cases. 
 - Backport to Java 11 LTS. 

# Releases

TBD

## Tentative Roadmap

The list of tasks: 

 - [x] Impliment the Paxos Paraliment Protocol for log replication. 
 - [x] Write a test harness that injects rolling network paritions. 
 - [ ] Implement a trivial replicated stack. 
 - [ ] Implement Implement cluster membership changes as UPaxos. 
 - [ ] Implement cluster membership changes as UPaxos over the base algorithm. 

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
