/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.Set;

/// The interface to provide a strategy for determining whether a quorum has been reached. UPaxos Quorums requires an understanding
/// of the current cluster configuration. That will be different at each log index. This means that
/// there can be different rules for determining overlapping quorums at different parts of the protocol. Voting weights
/// allow us to do specific optimizations such as the even node gambit. The papers such as UPaxos and FPaxos explains that
/// what we need is that any two quorums must overlap in at least one node. We can use different quorum strategies for the
/// prepare and accept phases. For example, we could use a simple majority for the `accept` phase and a weighted quorum
/// the `prepare` phase so that to the following primary-secondary cross-regional configuration:
///
/// - Use a six node cluster with two nodes in each region
/// - Put each node in a separate zone so that it is in a separate failure domain so unlikely that any two nodes will ever fail at the same time
/// - When in steady state use a weighted quorum with one region designated as the primary region.
/// - In each region set the two nodes to have a voting weight of 0. These are hot standby nodes in case of a regional failure.
/// - In the primary region set one node to have a voting weight of 2, the other to have a voting weight of 1
/// - In the secondary region set both nodes to have a voting weight of 1
/// - In the `accept` phase use the weighted quorum strategy. This will mean that when the primary contacts the other
/// node in the primary region the value is accepted without contacting the secondary region.
/// - In the `prepare` phase do not use weights use a simple majority. That requires one vote in the opposite region to
/// force any node in the secondary region to promote to being the leader.
/// - When you want to actually fail-over between regions the host application as a distributed system may need some things
/// like DNS updates or URL updates for the app to move to the other region.
/// - When you are initiation the cross region fail-over you can force the two nodes in the primary region to have a new
/// just set the voting weights to 1 on in the active region and 0 in the other region.
/// - This will cause the secondary region to suddenly have a majority for both `prepare` and `accept`.
///
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts);

  /// This is important to know when a majority write has been achieved.
  int clusterSize();

  enum QuorumOutcome {
    WIN, LOSE, WAIT
  }
}
