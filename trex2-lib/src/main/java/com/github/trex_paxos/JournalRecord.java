package com.github.trex_paxos;

public sealed interface JournalRecord permits Accept, Progress {
}
