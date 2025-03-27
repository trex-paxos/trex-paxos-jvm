/// This package contains the core classes and interfaces for the Trex Paxos implementation.
///
/// The library provides fault-tolerant distributed consensus through the TrexApp class:
/// `TrexApp<COMMAND,RESULT>` runs Paxos consensus on COMMAND objects across a cluster, then
/// transforms each chosen COMMAND into a RESULT locally at each node.
///
/// To use TrexApp, applications need to provide:
/// 1. A [com.github.trex_paxos.Journal] implementation backed by the application's database, allowing atomic commits of
///    both consensus state and application state
/// 2. A [com.github.trex_paxos.Pickler] implementation for thee COMMAND type
/// 3. A `TrexNetwork` that sends messages over the network
/// 4. A `Function<COMMAND,RESULT>` containing the core application logic to process each chosen COMMAND
///
/// Supporting classes and interfaces:
/// - [com.github.trex_paxos.Pickler]: Converts objects of type T to and from byte arrays. Required for COMMAND types which are the
///   host application command values that are passed between network nodes and written into the [com.github.trex_paxos.Journal].
/// - [com.github.trex_paxos.TrexEngine]: Manages timeouts and heartbeats around the core Paxos algorithm implemented in TrexNode.
/// - [com.github.trex_paxos.TrexNode]: Implements the core Paxos algorithm, processing messages and maintaining consistency.
/// - [com.github.trex_paxos.Progress]: Tracks the highest promised ballot number and highest fixed log index for a node.
/// - [com.github.trex_paxos.Journal]: Interface for crash-durable storage of Paxos protocol state.
/// - [com.github.trex_paxos.BallotNumber]: Orders proposals in the Paxos protocol by combining a counter with node identifier.
/// - [com.github.trex_paxos.QuorumStrategy]: Defines how voting quorums are calculated, allowing for different strategies.
///
/// In order to make it easy to pickle java records and sealed traits that permits only records there are two
/// utility pickler classes [com.github.trex_paxos.FlatRecordPickler] and [com.github.trex_paxos.SealedRecordsPickler].
///
/// The library focuses on correctness and performance while remaining agnostic to application specifics.
/// Architecture Note:
/// TrexApp handles network -> TrexEngine manages threading -> TrexNode implements core algo
/// Application callbacks happen synchronously during paxos() call while holding mutex
package com.github.trex_paxos;
