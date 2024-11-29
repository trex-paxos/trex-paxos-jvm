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

/// An AcceptResponse response back to a {@link Accept} message. We add the highestFixedIndex as more information to cause a leader to abdicate if it is behind.
///
/// @param from                  see {@link TrexMessage}
/// @param to                    see {@link DirectMessage}
/// @param vote                  whether wre have voted for or voted against the Prepare message based on our past promises.
/// @param highestFixedIndex additional information about the highest accepted index so that a leader will abdicate if it is behind.
public record AcceptResponse(byte from,
                             byte to,
                             Vote vote,
                             long highestFixedIndex
) implements TrexMessage, DirectMessage, SlotFixingMessage {
// TODO should we send the highest promise in case it is a nack?
}
