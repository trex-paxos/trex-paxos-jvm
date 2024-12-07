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

/// TrexMessage is the base interface for all messages in the protocol.
public sealed interface TrexMessage permits
    Accept,
    AcceptResponse,
    BroadcastMessage,
    Catchup,
    CatchupResponse,
    Fixed,
    DirectMessage,
    Prepare,
    PrepareResponse {
  /// @return the node in the cluster that sent this message.
  byte from();
}

