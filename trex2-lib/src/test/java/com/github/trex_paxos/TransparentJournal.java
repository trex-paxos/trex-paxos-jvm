package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.BallotNumber;
import com.github.trex_paxos.msg.NoOperation;
import com.github.trex_paxos.msg.Progress;

import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

class TransparentJournal implements Journal {
  public TransparentJournal(byte nodeIdentifier) {
    progress = new Progress(nodeIdentifier);
    fakeJournal.put(0L, new Accept(nodeIdentifier, 0, BallotNumber.MIN, NoOperation.NOOP));
  }

  Progress progress;

  @Override
  public void saveProgress(Progress progress) {
    this.progress = progress;
  }

  NavigableMap<Long, Accept> fakeJournal = new TreeMap<>();

  @Override
  public void journalAccept(Accept accept) {
    fakeJournal.put(accept.logIndex(), accept);
  }

  @Override
  public Progress loadProgress(byte nodeIdentifier) {
    return progress;
  }

  @Override
  public Optional<Accept> loadAccept(long logIndex) {
    return Optional.ofNullable(fakeJournal.get(logIndex));
  }

  @Override
  public void sync() {
    // no-op
  }

  @Override
  public long highestLogIndex() {
    return fakeJournal.lastKey();
  }
}
