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

/**
 * The interface to provide a strategy for determining whether a quorum has been reached. UPaxos Quorums requires an understanding
 * of the current cluster configuration. That will be different at each log index. FPaxos can do the even node gambit. This means that
 * there can be different rules for determining overlapping quorums at different parts of the protocol.
 */
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts);

  enum QuorumOutcome {
    WIN, LOSE, WAIT
  }
}
