// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
/// This package contains the core classes and interfaces for the Trex Paxos implementation.
///
/// The library provides fault-tolerant distributed consensus through the
/// `TrexService<COMMAND,RESULT>` which runs Paxos over generic COMMAND types to fix the order of the
/// commands. Once each node in the cluster learns a command has been fixed it will run a host application
/// callback. This will return a generic RESULT type at each node. This is used to then complete the client
/// future.
///
/// To use TrexService, applications need to provide:
/// 1. The [com.github.trex_paxos.Journal] implementation which may be writing to the host application's database,
///    allowing atomic commits of both consensus state and application state
/// 2. The [com.github.trex_paxos.Pickler] for the host application COMMAND type which is the only one passed over
///    the network.
/// 3. A [com.github.trex_paxos.network.NetworkLayer] that sends messages over the network. A fast UDP implementation
///    is provided.
/// 4. A command handler of type `BiFunction<Long, Command, RESULT>` containing the core application logic to process
///    each chosen command
///
/// Supporting classes and interfaces:
/// - [com.github.trex_paxos.Pickler]: Interface to serialize and deserialize message to ByteBuffer. The application
///   needs to set up one for COMMAND types that are the only ones sent over the network.
///   the [com.github.trex_paxos.Journal].
/// - [com.github.trex_paxos.TrexEngine]: Manages timeouts and heartbeats around the core Paxos algorithm implemented
///   in TrexNode.
/// - [com.github.trex_paxos.TrexNode]: Implements the core Paxos algorithm, processing messages and maintaining
///   consistency.
/// - [com.github.trex_paxos.Progress]: Tracks the highest promised ballot number and highest fixed log index for a node.
/// - [com.github.trex_paxos.Journal]: Interface for crash-durable storage of Paxos protocol state.
/// - [com.github.trex_paxos.BallotNumber]: Orders proposals in the Paxos protocol by combining a counter with node
///   identifier.
/// - [com.github.trex_paxos.QuorumStrategy]: Defines how voting quorums are calculated, allowing for different
///   strategies.
package com.github.trex_paxos;

