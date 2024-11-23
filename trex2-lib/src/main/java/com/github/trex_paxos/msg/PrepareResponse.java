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
package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.util.Optional;

/// A PrepareResponse is a response to a Prepare message. It contains the vote and the highest uncommitted log entry if any.
public record PrepareResponse(
    Vote vote,
    long highestAcceptedIndex,
    Optional<Accept> highestUncommitted
) implements TrexMessage, DirectMessage {

  public byte from() {
    return vote.from();
  }

  public byte to() {
    return vote.to();
  }
}
