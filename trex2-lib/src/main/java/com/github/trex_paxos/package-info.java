/// This package contains the core classes and interfaces for the Trex Paxos implementation.
///
/// The Trex Paxos library provides a robust and efficient implementation of the Paxos consensus algorithm.
/// It includes components for managing distributed consensus, handling client requests, and ensuring data consistency
/// across multiple nodes in a distributed system.
///
/// Key classes and interfaces in this package:
/// - `PaxosService`: An interface that defines how state is stored. It is expected that the host application will provide an implementation of this interface using a database or other durable storage mechanism.
/// - `Journal`: An interface that defines how state is stored. It is expected that the host application will provide an implementation of this interface using a database or other durable storage mechanism.
/// - `TrexEngine`: A wrapper class that manages the timeout behaviors around the core Paxos algorithm. Subclasses must implement the timeout and heartbeat methods.
/// - `BallotNumber`: Represents a ballot number in the Paxos algorithm. It is used to order proposals and is held as the latest promise in a nodes `Progress`
/// - `Progress`: The highest promised number and highest fixed log slot index of a node. This must be persisted to the journal as crash durable before messages are sent out. It is loaded at startup from the journal.
/// - `TrexNode`: The core class that implements the Paxos algorithm. It processes inbound messages, writes into the journal, and returns fixed command values and outbound messages.
/// - `UUIDGenerator`: As the JDK UUID class does not provide a way to generate time-ordered UUIDs. It is recommended to use this class to generate client message uuid {@link com.github.trex_paxos.Command#clientMsgUuid()} so that they are approximately time ordered.
/// - `Pickle`: A method to binary encode and decode the messages and paxos state. Hopefully soon Java will fix serialization of records so that this is no longer needed for future JVMs.
/// - `QuorumStrategy`: An interface that defines how quorums are calculated. This is because while in Paxos Made Simple uses a simple majority quorum later we can use UPaxos.
///
/// The library is designed to be used in high-performance, fault-tolerant distributed systems where consensus and
/// data consistency are critical.
package com.github.trex_paxos;
