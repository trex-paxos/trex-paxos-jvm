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
/// It will only return the accept messages for committed slots.
/// @param from                 see {@link TrexMessage}
/// @param to                   see {@link DirectMessage}
/// @param accepts              the list of fixed accepts above the slot index requested.
public record CatchupResponse(byte from,
                              byte to,
                              List<Accept> accepts
) implements TrexMessage, DirectMessage, SlotFixingMessage {
  // TODO should we also send the highest promise for when this is a nack to a prepare?
}
