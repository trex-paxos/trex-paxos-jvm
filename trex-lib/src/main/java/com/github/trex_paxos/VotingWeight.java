/*
 * Copyright 2024 - 2025 Simon Massey
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

/// We can optionally use voting weights to evaluate quoums.
/// FPaxos (Flexible Paxos) and UPaxos (Unbounded Paxos) use 
/// voting weights. 
public record VotingWeight(NodeId nodeId, int weight) {
    public VotingWeight {
        if (weight < 0) throw new IllegalArgumentException("Voting weight must be non-negative");
    }
    public VotingWeight(short id, int weight) {
        this(new NodeId(id), weight);
    }
}
