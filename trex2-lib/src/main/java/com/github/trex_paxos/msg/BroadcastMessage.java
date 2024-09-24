package com.github.trex_paxos.msg;

public sealed interface BroadcastMessage extends TrexMessage permits
    Accept,
    Commit,
    Prepare {
}
