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

import java.util.List;
/// CatchupResponse is a message sent by the leader to a replica in response to a Catchup message.
/// It will only return committed slots that the replica has requested. This is to avoid sending
/// lots of uncommitted messages during a partition where an old leader is not yet aware of a new leader.
public record CatchupResponse(byte from,
                              byte to,
                              List<Accept> catchup,
                              Commit commit
) implements TrexMessage, DirectMessage {
}
