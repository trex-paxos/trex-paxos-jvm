package com.github.trex_paxos;

public interface Journal {
    void saveProgress(Progress progress);

    void journalAccept(Accept accept);

    Progress loadProgress(byte nodeIdentifier);
}
