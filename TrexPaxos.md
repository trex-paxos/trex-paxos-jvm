# Cluster Replication With Paxos

This essay as originally published on WordPress in 2014 as [Cluster Replication With Paxos](https://simbo1905.wordpress.com/2014/10/28/transaction-log-replication-with-paxos/).

In my blog post [The Trial Of Paxos Algorithm](https://simbo1905.wordpress.com/2014/10/05/in-defence-of-the-paxos-consensus-algorithm/), I made the case for the defence of the Paxos algorithm. In this essay, we will delve into a tough problem that Paxos solves; state replication across a cluster of replicas using atomic broadcast. 

It turns out that maintaining primary ordering on secondary servers is straightforward to achieve using Paxos without affecting correctness or efficiency. This is done with the mathematically minimum number of message exchanges. 

This may be a surprising result to any reader who has looked at Zookeeper Atomic Broadcast [ZAB](http://web.stanford.edu/class/cs347/reading/zab.pdf) and [Raft](https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf). You don't need an external leader election service like the one used in the [Spinnaker](http://www.vldb.org/pvldb/vol4/p243-rao.pdf) or [PaxStore](http://link.springer.com/chapter/10.1007%2F978-3-662-44917-2_39) papers. 

To be clear, I am not claiming any new innovation or invention in this post; it is simply my attempt to demystify the subject. I assert that it is possible to implement the protocol described in the 2001 paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf) to make a safe and efficient cluster replication algorithm. For definitions of the messages and terminology used in this post, please refer to that paper.

A common confusion is not comprehending that Paxos is the [The Multi-Decree Parliament](https://lamport.azurewebsites.net/pubs/lamport-paxos.pdf) final solution not The Single-Decree Synod. Paxos is Multi-Paxos. 

Another common confusion is not comprehending that failover safety is designed into Paxos as proposal numbers must be unique to each leader; this is trivially achieved by encoding a "node unique number" into the least significant bits of the number used to compare messages. Remarkably, even papers published in late 2013 miss this core feature of the algorithm and choose an unsafe global log index number as the Paxos number, which is actually a violation of the algorithm, which then requires them to use a slow custom leader election mechanism to gain safety.

Yet another common confusion is that Paxos uses a [distinguished leader node](https://simbo1905.wordpress.com/2016/01/02/paxos-uses-leaders-multi-paxos-is-paxos/?subid1=20241121-1641-3860-b31e-81f8be90a45d). The list of common confusions seems endless. It very clearly evidences that no-one actually ever reads the actual original papers carefully. Obviously, it does not help that the very first paper eventually published in 1998 was a pedagogical blunder of collosial scale. 

## Problem Statement

Paxos is an algorithm for fixing many values across a cluster. This is known as the consensus problem:

> Assume a collection of client processes which may propose a value. A consensus algorithm ensures that a single one amongst the proposed values is chosen.

If that seems two abstract consider a sports ticket selling website where the last ticket to the superbowl is on sale. Thousands of web browser (`clients`) will offer (`propose`) the details (`value`) of different people be applied (`fixed`) against that unique ticket number (`slot`). 

The algorithm lets a mathematical majority of nodes agree on many values concurrently. Chaining values together into a meaningful protocol to provide a reliable service is left as an exercise to the reader. 

It helps to have a simple example in mind. For this discussion, we assume we want to replicate a file-backed map as a trivial key-value datastore across three different servers. In practice, any client-to-server network traffic can be replicated using the approach detailed in this post. 

One thing which may cause confusion is that your application may label "value" as meaning one thing (i.e. something held in a replicated map), but Paxos calls "value" the next command you are trying to get consistent across the cluster us as a binary encoding of a `put(k,v)` or `remove(k)` remote proceedure call.

In our map example above the operations do not commute; they need to be applied in the same order at every node in the cluster else the maps will not match the leaders. If we label each command with a sequential index we can enforce the ordering. 

## Multi-Paxos Numbers For Replication

The description of multi-Paxos on Wikipedia (as at late October 2014) includes a counter:

> To achieve multi-Paxos, the instance number `I` is included along with each value. 

The paper Paxos Made Simple clarifies that it is the absolute counter of the number of instances of the algorithm. The leader sends `accept(I,N,V)` where `I` is the instance counter, `N` is the proposal number unique to a leader, and `V` is the value being proposed by the leader for that instance. I am going to clarify the definition of the counter to be more specific:

> Let `S` be the logical log index or "slot", that is included along with each value, which must be made contiguous and unique across all committed values at all nodes.

Each `S` is logically a slot in commit history into which leaders propose values, for which a value must be fixed, and which must be applied in order. The [Spinnaker](http://www.vldb.org/pvldb/vol4/p243-rao.pdf) paper describing transaction log replication with Paxos also uses this definition. 

Clients are not shown the effect of the command at each slot until after it is acknowledged by a majority of nodes in the cluster. At which point the Paxos algorithm ensures that the value at the slot will not change. The log index allows for consensus to be performed in parallel on different slots using the same `N`. A leader may stream `accept(S,N,V)` messages in log order using `S,S+1,S+2,..,S+i`. 

It is a common misconception that the original Paxos papers don't use a stable leader. In Paxos Made Simple, on page 6 in the section entitled The Implementation, Lamport wrote:

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

This is simply achieved using the "Phase 1" messaging of `prepare` and the response `promise`. There is no requirement to perform a separate leadership election. Simple hearbeats and timeouts can be used. 

Due to leadership changes, a particular value may be proposed into a given slot `S` by one leader and then fixed by another using a different `N`. Leader failovers can also cause different values to be proposed into the same slot by different leaders. Paxos requires that `N` is unique to a node so there cannot be a clash between two leaders. We can make unique numbers by encoding the node identifier in the lower bits of the number. A counter can be used for the higher bits. 

In this essay we will use a decimal notification such as `N=45.3` where the node number is `3` which is held in the lower decimal part `0.3` and the counter is the whole part `45`. In practice we can use binary and any number of bytes we chose for the counter and the node identifier.  

If, during a network partition, we have multiple nodes attempting to lead, who all got a different value accepted into the same slot by a minority, with no majority, the round fails. 

Each leader will use a new higher `N'` at the next round to attempt to fix a value into that slot with a fresh `accept(S,N',V)` message. This means that for any given `{N,S}` pair, there is only one unique proposed `V`.

## Choosing A Value: Fix It And Then Commit It

We need to consider when a value is fixed, how that is learnt, and when it is applied in log order at each node. 

With Paxos, a value cannot change when it has been accepted by a majority of nodes in the cluster. Any node in the cluster can discover the value fixed at any given slot by asking each node what value they hold at each log slot. 

Whatever value is held by a majority of nodes is the fixed value. To reduce messages we can have the leader listen to the accept response messages of followers. It can then send a short `commit(S,N)` message when it learns a value has been accepted by a majority of nodes. The concept of a commit message is not covered in the original papers but is a standard optimisation known as a Paxos "phase 3" message. Yet we do not need to send it in a seperate network packet. 

An alternative to the commit message is to simply have all nodes exchange message to learn which values are fixed at each slow. The algorithm ensures once a value is fixed it is always fixed. So any mechanism can be used to learn which values have been fixed. Below we will introduce a `catchup` message to allow nodes to request retransmission of values that were sent in lost `accept` messages. This is a simple "learning" mechanism. 

Each leader only proposes a unique `V` for any `{S,N}` pair. If it does not get a successful response it will increment the counter within the higher bits of `N` in any new messages. The `N` is unique to each node by encoding the node identifier into the lower bits. If followers see a `commit(S,N)` and it has seen the corresponding `accept(S,N,V)` it has now learnt which exact `V` is fixed at the slot `S`.
  
To reduce network roundtrips we can simply piggyback the the short commit message at the beginning of the next leader message. Trex uses one byte for the message type, one byte for the node identifier, four bytes for the counter, and eight bytes for the slot number. This means that a commit message is just fourteen bytes placed at the beginning of the next accept message sent from the leader. 

When each follower has learnt that all prior slots have been fixed it can "up-call" to the value `V` to the host application. This is an application specific callback that can do whatever the host application desires to do. The point being that every node will up-call values in the same order. 

Recall that the values are actually remote proceedure call commands that are applied to the host application state. In our example of a k-v store these are `put(k,v)` or `remove(k)` operations. Trex simply uses byte arrays. The user of the library can put whatever they want into the byte arrays (e.g. json, protobuf, Avro). 

When there is no message loss, followers will see accept and commit messages packed together into network packets. The leader can stream accept messages such that there can be multiple outstanding values with the highest committed slot lagging behind. The challenge is when the follower sees gaps in the `S` numbers of either accept messages or commit messages due to lost or reordered messages. 

Interestingly a leader may die before it sees a majority of accept responses; so a value may be fixed, and cannot change, but no-one is aware. This scenario will be covered in the next section. 

## Leader Election

The leader can heartbeat messages. When a follower timeout on leader heartbeats it can increment the counter in `N` to come up with a new new unique number for the slot higher than it knows has been fixed. Node number `2` will encode its identifier in the lower bits and increment the counter in the higher bits. If the counter was previously `3` it will issue `prepare(S,N=4.2)` and the election is simply whether it receives a majority positive responses. A node can and should vote for itself. So in a three node cluster a node it only needs to exchange one message to become elected. This is optimal. We must always have a crash proof disk flush on each node before each message is sent out (else non-volatile memory or some other crash proof mechanism).  

The positive prepare responses are known as `promise` messages. These are leader election votes. In a three node cluster a node first votes for itself, and it only needs to see on other positive responses to have been elected. It can then choose a value and issue `accept(S,N=4.2,V=32)`. To get this to work we simply need other nodes to not aggressively interfere based on heartbeats and randomised timeouts. 

If messages exchanges are in the order of milliseconds we can use a randomised timeout lower bounded to something like thirty milliseconds and upper bounded to a hundred milliseconds. What is optimal is of course entirely dependent on the network and disk properties of a given cluster. Getting it wrong can lead to a leader duel which is a form of live-lock. 

This is simply CAP theorem in practice. You want `C` (consistency) in the case of `P` (network partitions) so you are trading off some `A` (avaiablity). You are not entirely giving up on availability you are simply picking your failure modes such that you never expericence any lack of `C` while you do experiece a lack of `A` due to too much real world `P`. 

Of course if people want a faster failover they can implement more complex leader election mechanisms. The simplicity of randomised timeouts is to my mind very elegant. Exactly what might be a elegant to you depend on your application. A library like Trex should aim to be compatible with external failure detection or leader detection solutions. 

 
## The Leader Take-Over Protocol

What happens for slots above the maximum committed log index slot where a dead leader issued accept messages but no commit messages were seen? The new leader runs Paxos full rounds to fix values into these slots. 

It issues a `prepare(N,S)` for all slots `S, S+1, S+2, .. S+i` not so far learnt to have been fixed. Each other node responds with a `promise` message which is the highest uncommitted `{N,V}` pair at each slot `promise(S,N,V)`. 

Obviously, a node should never issue a promise message for a slot `S` which it knows was previously fixed no matter how hight the ballot number used in the prepare message. 

When the new candidate leader recieves a majority of promises it then selects the value with the highest `N` at each slot `S` which it then attempts to fix by sending out a fresh accept message for that `V` under its own higher `N'`.

If the new leader gets a positive majority accept response it knows the value at the slot is now fixed. The Spinnaker paper refers to this "slot recovery" phase as the leader takeover phase. 

In practice it helps to issue negative responses to a `prepare` message help each node gossip about the state of the netowork and possible other leaders. We want to discover whether the other node has already committed a higher slot value and highest promised ballot number at the other node. We therefore use a `PrepareResponse` message that may be a positive acknowledgement "ack" which is a promise, or a negative acknowledgement "nack", which informs the node why the promise could not be made. 

If the new leader is not aware of any uncommitted slots, it can send a prepare for the slot just higher than the last it committed. It may be the case that the new leader missed some accept messages from the previous leader. It is unaware of the full range of slots it needs to fix. One node in the majority knows the highest slot index proposed by the last leader. The ack messages holding the `promise` can state the highest accepted log index at each node. This allows the new leader to learn the full range of slots it needs to fix. It can then send out additional prepare messages as required to correctly fix all slots. 

Probing and filling in the previous leader's uncommitted slots is a form of crash recovery. The Paxos Made Simple paper says that if the new leader finds a slot with no accept message due to lost messages, it should fix a no-op value into that slot. This does not affect the correctness as the new leader can choose any value; it just speeds up recovery. There is the question of what `N` number to use in the prepare  messages sent during the leader takeover. The new leader should choose a number higher than the maximum number it last promised or last accepted.

It is important to understand that the same value being accepted under different term numbers. Consider a three node cluster where the leader with node identifier 1 at term N=2.1 choses value V=32 at slot S=10 when the network went crazy as it attempts to send accept(S=10,N=1.1,V=32). This can lead to some interesting scenarios depending on which messages can get through or which node are crashed. 
If accept(S=10,N=1.1,V=32) never arrives after a timeout a new node will run the recovery protocol. This might be node 2 which increments the counter and adds its node identifier to the lower to give N=2.2. Image other old leader remains crashed for hours. The new leader 2 selects V=128 for slot S=10 and sends accept(S=10,N=2.2,V=128). This reaches node 3 and now slot S=10 is forever fixed at V=128. Eventually node 1 rejoins the cluster having incrementing its term to N=3.1. It will never accept the new leaders message accept(S=10,N=2.2,V=32). Yet the value fixed at slot is fixed at V=128. Node 1 must learn that the value was fixed at V=128 from the new leader. The new leader should realise that node 1 cannot accept messages under the current leader term of N=2.2 which it can observe via negative response messages. The new leader can simply increment its counter until it gets a new higher N=4.2. It can the make an instantaneous promise to itself for the next slot then issue the next accept message using N=4.2.
Alternatively consider if the accept message accept(S=10,N=1.1,V=32) only arrived at node 3. The new leader node 2 will learn of of the value V=32 via the response to the prepare  that it sends for slot S=10.  It will create a new accept(S=10,N=2.2,V=32) and self accept then transmit. Node 3 must accept the new message. It already had V=32 at slot S=10 yet it must give a positive acknowledgement back to 2 for the new leader to learn that the value has been fixed. This means that while each {N,S} is a unique V the opposite is not true. The same V will be sent for the same slot under different numbers. Intuitively what is happening here is that the cluster is gossiping until every node in the cluster has exchanged enough messages to understand what value is fixed.
Retransmission
A leader can stream accept messages without awaiting a replay for each slow. If we stream five messages, and three are dropped, a leader might learn that the first and fifth slot are fixed. How about the other three slots? That depends on whether any of the messages got through. 
Imagine that the leader now crashes and comes back. It then hears a new commit message fro a much higher slot. How does it learn what was fixed for the three slots while it was crashed? 
To cover this case we can add a catchup message. This can state the last slot known to be committed. This can be sent to any node in the cluster. The catchup response will send the values that are fixed above the slot in the request. This is simply a method to efficiently learn the fixed values.
A leader can heartbeat commit messages. Then a node will likely hear about the higher fixed slots first from the leader. The new leader will typically have the most up to date log and knowledge of what is fixed. This means that the catchup message will likely be sent to the leader. Once again the leader a pack many messages into a single network packet. It can send the catchup response, the next accept, and the next commit all within a single network frame. 
End Notes
The next post will cover whether read operations have strong or weak consistency. On GitHub, there is now source code that implements multi-Paxos as described above.

© Simon Massey, 2014 - 2024, CC BY-SA

Edit:  I recently came across this overview of consensus algorithms.  The above description and the code on GitHub does "active consensus for Multi-Consensus-Prefix-Ordering". The ordering is trivially achieved by not calling up to the host application out-of-order.

[1] In applications where operations are known to commute, we can run multiple primary ordered versions of the algorithm in parallel by pipelining.


© Simon Massey, 2014 - 2024, CC BY-SA