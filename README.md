## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

Checkout the
post [Cluster Replication With Paxos for the Java Virtual Machine](https://simbo1905.wordpress.com/2014/10/28/transaction-log-replication-with-paxos/)
for a description of this
implementation
of [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf).

This is a work in progress as more tests are to be written. At this point it is not recommended for production use.

It will use the latest Java 22+ for Data Oriented Programming to build an embeddable implementation of the Paxos Part
Time Parliament Protocol using the algorithm described in Paxos Made Simple algorithm for state replication.

### Goals

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
