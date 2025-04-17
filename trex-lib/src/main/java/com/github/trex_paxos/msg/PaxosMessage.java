// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

/// The PaxosMessage interface is a sealed interface that is used to define the messages that are part of the
/// Paxos protocol that are named in the paper "Paxos Made Simple" by Leslie Lamport. Additional invariant checks are
/// added to ensure that only these messages alter the state of the TrexNode.
public sealed interface PaxosMessage permits Prepare, Accept {
}
