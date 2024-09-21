package com.github.trex_paxos.msg;

public sealed interface JournalRecord permits Accept, Progress {
}
