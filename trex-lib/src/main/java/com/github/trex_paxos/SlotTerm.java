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

/// Each leader must increment the counter in the ballot number each time it attempts to lead. This means that the
/// pairing of a slot with a ballot number is unique. BallotNumbers are also unique to a node as it encodes the nodeIdentifier.
/// Nodes will not reuse the counter for different slots. This means a slot term is associated with a specific value
/// chosen a node attempting to lead. During the leader takeover process the new leader will increment their counter
/// and may choose a value that is returned from a prepare response from the previous leader. This means that
/// the same value may be at the same slot logIndex on different nodes associated with a different ballot number.
public record SlotTerm(long logIndex, BallotNumber number) implements Comparable<SlotTerm> {
  public SlotTerm {
    if (logIndex < 0) {
      throw new IllegalArgumentException("logIndex must be >= 0");
    }
    if (number == null) {
      throw new IllegalArgumentException("number must not be null");
    }
  }
  // FIXME this needs to compare number()
  @Override
  public int compareTo(SlotTerm o) {
    return Long.compare(logIndex, o.logIndex);
  }

  public short era() {
    return number().era();
  }
}
