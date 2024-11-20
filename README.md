
## Trex2: An Embeddable Paxos Engine 

Checkout the [blog posts](https://simbo1905.wordpress.com/2016/01/09/trex-a-paxos-replication-engine/) for a description of this implementation of [Paxos Made Simple](https://courses.cs.washington.edu/courses/cse550/17au/papers/CSE550.paxos-simple.pdf).

This is a work in progress. It will use the latest Java 22+ for Data Oriented Programming to build an embeddable implementation of the Paxos Part Time Parliament Protocol using the algorithm described in Paxos Made Simple algorithm for state replication.

### Goals

 - Demonstrate state replication with the Partime Parliment Protocol (aka Multi Paxos).
 - Ship with zero third-party libraries outside of the java base packages. 
 - Target the Java 25 LTS version for any sophisticated demos. 
 - Target the Java 22+ version for the core library. 
 - Consider a backport to Java 21 LTS only when Java 25 LTS is out to see what would be sacrificed. 

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

 - [x] Impliment the Paxos Paraliment Protocol for log replication. 
 - [x] Write a test harness that injects rolling network paritions. 
 - [ ] Implement a trivial replicated stack. 
 - [ ] Implement Implement cluster membership changes as UPaxos. 

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
