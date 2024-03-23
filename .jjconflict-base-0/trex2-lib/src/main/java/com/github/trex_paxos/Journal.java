package com.github.trex_paxos;

public interface Journal {
    void journalProgress(Progress progress);

    void journalAccept(Accept accept);
}
