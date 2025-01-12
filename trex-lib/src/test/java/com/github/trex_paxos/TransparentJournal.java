package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;

import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

public class TransparentJournal implements Journal {
  public TransparentJournal(short nodeIdentifier) {
    progress = new Progress(nodeIdentifier);
    fakeJournal.put(0L, new Accept(nodeIdentifier, 0, BallotNumber.MIN, NoOperation.NOOP));
  }

  Progress progress;

  @Override
  public void writeProgress(Progress progress) {
    this.progress = progress;
  }

  NavigableMap<Long, Accept> fakeJournal = new TreeMap<>();

  @Override
  public void writeAccept(Accept accept) {
    fakeJournal.put(accept.slot(), accept);
  }

  @Override
  public Progress readProgress(short nodeIdentifier) {
    return progress;
  }

  @Override
  public Optional<Accept> readAccept(long logIndex) {
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
