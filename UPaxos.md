Fully Concurrent Configuration Changes
=========

Pipelining is when work is started on new requests before existing requests have completed. Paxos 
allows us to pipeline by streaming accept messages from the leader to the cluster without needing 
to await any responses. In the "UPaxos" paper [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf) the section "Fully Concurrent Configuration Changes" describes how cluster reconfigurations can be performed in Paxos without limiting pipeline concurrency. This is safe as long as quorums overlap across configuration eras.

The advantages over alternatives are: 

- No fixed pipeline length limits (unlike Dynamic Paxos)
- No need to stop service (unlike Stoppable Paxos)
- Handles arbitrary delays in reconfiguration messages
- Allows unlimited concurrent requests during transitions

This library optionally supports UPaxos by allowing an optional `era` to be set in the BallotNumber

```java
public record BallotNumber(
        short era, /* optional and defaulted to zero when not used */
        int counter, /* incremented when nodes begin the leader takeover protocol */
        short nodeIdentifier /* unique to each node */ )
    implements Comparable<BallotNumber>  
{
  /// comparison is ordered era -> counter -> nodeIdentifier 
  @Override public int compareTo(BallotNumber that) { /*...*/ }
}
```

Key Mechanism
---------

* **Era-based reconfiguration:** Nodes transition between configuration "eras" by the leader choosing a cluster reconfiguration command. These are denoted `e` and `e+1`
* **Overlap requirement:** New configurations must ensure quorums overlap between adjacent eras to maintain consistency during transitions
* **Safety:** A new leader that runs the leader takeover protocol using the configuration of either `e` and `e+1` will see all fixed message of the prior leader

### Reconfiguration Process Overview

1. **Propose new configuration**
   - The operator enters a new configuration for era `e+1`
   - The leader chooses this as the next command value to stream in the next accept message and enters a special "overlap mode".
   - While in "overlap mode" the leader sends messages to nodes using numbers of both the `e` and `e+1` eras different 
   - The leader casting vote technique used to avoid stalls due to lost of reordered messages. 

2. **Leader preparation**
    - The leader splits the cluster to create two quorums where it is in both: 
      1. A prepare quorum for `e+1` where its own vote will achieve a promise quorum 
      2. An accept quorum `e+1` where its own vote will fix a value
    - The leader no longer sends any prepare `e+1` ballot numbered message to itself until it learns from other nodes in the prepare quorum that it will be the casting vote.  
    - The leader increments the ballot number to era `e+1` to send messages prepare messages to the prepare quorum. 
    - The leader continues to stream accepts messages to the other nodes and itself in the in the accept quorum using the old era `e` number. 
    - The leader may speculatively stream accept messages to the other nodes in the prepare `e+1` quorum using accept messages with the `e+1` number. 

3. **Casting vote transition**
   - The leader continues to stream accept messages to both overlapping quorums using the appropriate era number for each node. 
   - When the leader hears enough prepare responses from the other nodes in to prepare such that its own vote achieves a quorum it instantaneously sends the prepare message of `e+1` to itself.   
   - It then issues the `e+1` prepare message to the rest of the cluster and exits the "overlap mode"
   
Client requests continue being processed without pipeline stalls due to quorum overlaps. The leader makes the "casting vote" of when the new number of the new era has been accepted by a quorum of nodes. 

# Implementation detail

The leader manages era transitions transparently to clients, using ballot numbers that encode era information. Quorum intersection properties ensure safety despite concurrent activity in multiple eras.

For mathematical details and formal proofs, see sections IV-D and V of the original paper.  [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf) 
A copy is in this repo. 

The paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) explains that when a node makes a promise to reject proposals of less than a given number it makes that promise for an infinite number of future rounds of paxos. This means that we can stream accept messages for without issuing a prepare message. If a node sees an accept message with a ballot number higher than the one it has previously promised it must promise to the higher ballot number in the accept message. This is clarified in a video lecture by Lamport where he says that detail was always in the formal TLA+ specification of Paxos yet was not explicitly mentioned in his 2001 paper.

This means that we do not need to send a prepare message to implement UPaxos. We can use only accept messages. This can be intuitively understood that we are fusing a `prepare` and `accept` message together in the correct order for every interaction during the cluster reconfiguration.
