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

/// The roles used by nodes in the paxos algorithm. The paper Paxos Made Simple by Leslie Lamport very clearly states:
///
/// > A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm
///
/// This means we have a leader. We also have followers who have not yet timed-out on the leader. Finally, we have the
/// recover role which is a node that is sending out prepare messages in an attempt to fix the values sent by a prior leader.
public enum TrexRole {
  /// A follower is a node that is not currently leading the paxos algorithm. We may time out on a follower and attempt to become a leader.
  FOLLOW,
  /// If we are wanting to lead we first run rounds of paxos over all known slots to fix the values sent by any prior leader.
  RECOVER,
  /// Only after we have recovered all slots known to have been sent any values by the prior leader will we become a leader
  /// who no longer needs to recover any slots.
  LEAD
}
