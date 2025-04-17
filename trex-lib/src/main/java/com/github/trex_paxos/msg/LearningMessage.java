// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

/// The LearningMessage interface is a sealed interface that is used to mark the messages that cause the node to
/// learn that a log index slot has been fixed. This interface is used to validate the invariants of the Trex Paxos protocol.
public sealed interface LearningMessage permits AcceptResponse, CatchupResponse, Fixed {
}
