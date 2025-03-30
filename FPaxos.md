# The FPaxos "Even Nodes" Optimisation

by simbo1905 September 30, 2016

This was originally published on my blog at [The FPaxos “Even Nodes” Optimisation ](https://simbo1905.wordpress.com/2016/09/30/the-fpaxos-even-nodes-optimisation/)

Up until 2016, it was well understood that the optimal size for typical Paxos clusters is three or five nodes. With typical replication workloads, it was known that four or six nodes clusters are no better, and in fact worse, than having one less node. The FPaxos "Flexible Paxos" paper changes the rules of the consensus game with the "even nodes" optimisation.

Why was even numbers of nodes a bad idea? The original proof of the correctness of Paxos makes use of simple majorities. The formula $$2F+1$$ is used to figure out how many nodes you need in a cluster to survive F failures. So a three node cluster can survive one node dying. A five node cluster can survive two dead nodes. Disappointingly a four node cluster can only survive one node dying; it is no more resilient than a three node cluster. Likewise, a six node cluster is no more resilient than a five node cluster. So an even number of nodes is no better; but why is it worse than using one less node? Surely more is better? Nope.

The FPaxos paper has references to both quorum theory and experimental evidence that throughput drops as you increase the quorum size. An intuitive explanation is as follows. A leader can pick an arbitrarily high ballot number that it self-accepts. So a leader in a three node cluster only needs to see one follower response to obtain a majority of two. A leader in a four node cluster needs to see a second response message to obtain a majority of three. The leader has to wait until the slowest of two response arrives. The maths answer is that the birthday paradox effect bites you. Even with a low probability of any one response being slow there is a compounding effect when you need to wait for multiple responses. A three node cluster is optimal as the leader can accept its own response and only needs to wait for one response from one other node. In practice, a five nodes cluster is considered a good size. You can be fixing one dead node when a second one dies and still not have any downtime.

## Detour: Handling Even Number of Nodes

Just because it is inadvisable not to have an even number of nodes doesn't mean that it is impossible. A Paxos library such as TRex needs to handle the case where you have an even number of nodes. A cluster shouldn't lockup on a split vote in an even number of nodes. TRex treats a split-vote in an even number of nodes as a failed round. Failed rounds can only occur during a leader failure-over where two nodes are attempting to lead. Randomised timeouts with exponential back-off will eventually resolve any split vote. If you want to avoid them entirely just deploy an odd numbers of nodes. Alternatively, we can assign voting weights which sum to an odd number as documented in the UPaxos paper.

## The Even Nodes Optimisation

Back to the even nodes optimisation. The remarkable discovery with Flexible Paxos is that with four nodes you don't need to wait for a majority response to accept messages; only the prepare messages. The leader does not have to wait for two accept responses it only needs to wait for a single response. So the throughput and latency of a four node cluster under steady state running can be more like a three node cluster than a five node cluster. Why?

It turns out that simple majorities are only one way of ensuring correctness. Yet it isn't optimal one for many scenarios such as when you have an even number of nodes. What you actually need is any set of quorums which satisfies $$|P|+|A|>N$$ where N is the total number of nodes, $$|P|$$ is the number of nodes that made promises, and $$|A|$$ is the number of nodes that accepted values. Why?

What you need to ensure correctness is that a new leader must be guaranteed to see the highest accepted value of the previous leader. If I have four nodes, and two accepted the last value sent by a dead leader (the leader and one other), and the new leader obtains promises from three live nodes (everyone but the dead leader), then one of those promises will include the last value proposed by the previous leader. Putting the figures into the formula we see $$3 + 2 > 4$$ which confirms our reasoning.

The fact that with even nodes you have to wait for one less accept response is an amazing discovery. It is also very simple to code. Adding it to the TRex Paxos library took only a couple of hours. Most of which was refactoring the code to make the quorums strategy pluggable.

The optimisation also means that a four node cluster is a little more resilient. If you split four nodes across two racks and the network partitions the racks your leader can still make progress. Only if the leader dies during the partition do you have a problem. Without the even nodes optimisation if the link between the two racks goes down the leader cannot process any client commands. With the optimisation only if the leader dies during the partition do you have a problem as a new leader cannot get a majority to when running the leader takeover phase. Neat.

In contrast to this new result, we have had decades of ever more complex and niche enhancements. Complexity in software engineering is rarely, if ever, justified by marginal improvements. After eighteen years the discovery of a simple, and widely applicable optimisation, on such a heavily studied algorithm is remarkable. Queue spontaneous rejoicing and wild applause amongst consensus fans.
