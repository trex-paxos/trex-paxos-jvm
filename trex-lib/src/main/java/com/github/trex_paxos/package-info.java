/// This package contains the core classes and interfaces for the Trex Paxos implementation.
///
/// The library provides fault-tolerant distributed consensus through the TrexApp class:
/// `TrexApp<VALUE,RESULT>` runs Paxos consensus on VALUE objects across a cluster, then
/// transforms each chosen VALUE into a RESULT locally at each node.
///
/// To use TrexApp, applications need to provide:
/// 1. A [Journal] implementation backed by the application's database, allowing atomic commits of
///    both consensus state and application state
/// 2. A [Pickler] implementation for thee VALUE type
/// 3. A `TrexNetwork` that sends messages over the network
/// 4. A `Function<VALUE,RESULT>` containing the core application logic to process each chosen VALUE
///
/// Supporting classes and interfaces:
/// - [Pickler<T>]: Converts objects of type T to and from byte arrays. Required for VALUE types which are the
///   host application command values that are passed between network nodes and written into the [Journal].
/// - [TrexEngine]: Manages timeouts and heartbeats around the core Paxos algorithm implemented in TrexNode.
/// - [TrexNode]: Implements the core Paxos algorithm, processing messages and maintaining consistency.
/// - [Progress]: Tracks the highest promised ballot number and highest fixed log index for a node.
/// - [Journal]: Interface for crash-durable storage of Paxos protocol state.
/// - [BallotNumber]: Orders proposals in the Paxos protocol by combining a counter with node identifier.
/// - [QuorumStrategy]: Defines how voting quorums are calculated, allowing for different strategies.
///
/// In order to make it easy to pickle java records and sealed traits that permits only records there are two
/// utility pickler classes [RecordPickler] and [PermitsRecordsPickler].
///
/// The library focuses on correctness and performance while remaining agnostic to application specifics.
package com.github.trex_paxos;
