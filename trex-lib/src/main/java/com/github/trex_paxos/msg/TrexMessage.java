// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
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
    PrepareResponse  {
  /// @return the node in the cluster that sent this message.
  short from();
}

