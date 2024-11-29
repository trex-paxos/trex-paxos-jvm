package com.github.trex_paxos.msg;

/// The SlotFixingMessage interface is a sealed interface that is used to mark the messages that cause the node to
/// learn that a log index slot has been fixed. This interface is used to validate the invariants of the Trex Paxos protocol.
public sealed interface SlotFixingMessage permits AcceptResponse, CatchupResponse, Commit {
}
