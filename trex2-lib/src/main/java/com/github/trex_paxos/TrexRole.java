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

/// The roles used by nodes in the paxos algorithm.
public enum TrexRole {
  /// A follower is a node that is not currently leading the paxos algorithm. We may time out on a follower and attempt to become a leader.
  FOLLOW,
  /// If we are wanting to lead we first run rounds of paxos over all known slots to fix the values sent by any prior leader.
  RECOVER,
  /// Only after we have recovered all slots will we become a leader and start proposing new commands.
  LEAD
}
