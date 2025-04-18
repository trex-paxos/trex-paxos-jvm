// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
/// The network package provides transport abstractions for the Paxos implementation.
///
/// Key interfaces:
/// - `TrexNetwork`: Core interface for sending/receiving messages between nodes
/// - `Channel`: Defines CONSENSUS and PROXY channels
/// - `NodeId`: Unique node identifier within cluster
/// - `NetworkAddress`: TrexNetwork location abstraction
/// - `NodeEndpoints`: Cluster topology management
///
/// Design characteristics:
/// 1. Only COMMAND objects are sent between nodes
/// 2. RESULT objects are computed locally at each node
/// 3. Transport agnostic to support different network implementations
package com.github.trex_paxos.network;
