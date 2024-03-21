
## Trex2: Embeddable Paxos Engine 

Checkout the [blog posts](https://simbo1905.wordpress.com/2016/01/09/trex-a-paxos-replication-engine/) for a description of this implementation of [Paxos Made Simple](https://courses.cs.washington.edu/courses/cse550/17au/papers/CSE550.paxos-simple.pdf).

This is a work in progress. It will use the latest Java 22 Data Oriented Programming and Structured Concurrency to build 
a correct and modular implementation of the Paxos Made Simple algorithm for state replication. The goal is to be both correct 
and efficient in providing strong consistency to the JVM ecosystem.


## Building

TBD

## Java Clustered Stack Demo

TBD

# Releases

TBD

## Tentative Roadmap

The list of tasks: 

 - [ ] Port the paxos protocal messages over from the scala version to be records with serialization. 
 - [ ] Port the scala partial functions of the algorithm over to Java 22 as destructuring switch expressions.
 - [ ] Write a test harness to test the algorithm. (This could be to simply run the original scala test harness here.)
 - [ ] Implement cluster membership changes as UPaxos over the base algorithm. 
 - [ ] Implement leader election as quorum reads over the cluster. 

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
