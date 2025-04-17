// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
/// The msg package contains the core message types used in the Trex Paxos implementation.
/// This package implements the core Paxos protocol as described in "Paxos Made Simple"
/// by Leslie Lamport, with additional messages "learning" messages for nodes to learn from
/// the leader which values have been fixed or to catch up on missed messages.
///
/// Record Types:
/// - `Prepare`: First message in Paxos protocol for leader election and recovery.
/// - `PrepareResponse`: Response to Prepare containing vote and highest unfixed log entry.
/// - `Accept`: Second message in the Paxos protocol, used to propose a command for acceptance.
/// - `Fixed`: Leader's notification of newly fixed log index, also used as heartbeat.
/// - `AcceptResponse`: Response to Accept message, includes voting status and highest fixed index.
/// - `Catchup`: Request from replica to leader for retransmission of lost `Accept` messages.
/// - `CatchupResponse`: Leader's response containing sequential fixed `Accept` messages above requested slot.
/// - `Value`: A id that may be chosen by a leader to fix in a slot.
///
/// For the purposes of validating the invariants the following two interfaces are used:
/// - PaxosMessage types are the only messages that can alter the promise of a node.
/// ```
/// PaxosMessage
/// ├── Prepare
/// └── Accept
///```
/// - LearningMessage types are the only messages that can alter the fixed log index of a node:
/// ```
/// LearningMessage
/// ├── AcceptResponse
/// ├── CatchupResponse
/// └── Fixed
///```
///
/// For the purposes of transmitting messages within the cluster the `BroadcastMessage` and `DirectMessage` interfaces are used:
///
/// ```
/// TrexMessage
/// ├── BroadcastMessage
/// │   ├── Accept
/// │   ├── Fixed
/// │   └── Prepare
/// └── DirectMessage
///     ├── AcceptResponse
///     ├── Catchup
///     ├── CatchupResponse
///     └── PrepareResponse
///```
package com.github.trex_paxos.msg;
