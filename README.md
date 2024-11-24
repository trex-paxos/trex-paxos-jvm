
## Trex2: An Embeddable Paxos Engine 

Checkout the post [Cluster Replication With Paxos for the Java Virtual Machine](https://simbo1905.wordpress.com/2014/10/28/transaction-log-replication-with-paxos/) for a description of this implementation of [Paxos Made Simple](https://courses.cs.washington.edu/courses/cse550/17au/papers/CSE550.paxos-simple.pdf).

This is a work in progress. It will use the latest Java 22+ for Data Oriented Programming to build an embeddable implementation of the Paxos Part Time Parliament Protocol using the algorithm described in Paxos Made Simple algorithm for state replication.

### Goals

- Demonstrate state replication with The Part-Time Parliament Protocol (aka Multi Paxos).
- Ship with zero third-party libraries outside the java base packages.
- Target the Java 25 LTS version for the final version.

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
 - [ ] Implement a trivial replicated k-v store. 
 - [ ] Implement Implement cluster membership changes as UPaxos. 
 - [ ] Add in optionality so that randomised timeouts can be replaced by some other leader failover detection and voting mechanism (e.g. JGroups).  

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
