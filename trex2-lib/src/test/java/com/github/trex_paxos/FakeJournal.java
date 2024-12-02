package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.BallotNumber;

import java.util.Optional;

public class FakeJournal implements Journal {
  private final byte nodeIdentifier;
  private final BallotNumber promise;
  private final long highestFixed;

  public FakeJournal(byte nodeIdentifier, BallotNumber promise, long highestFixed) {
    this.nodeIdentifier = nodeIdentifier;
    this.promise = promise;
    this.highestFixed = highestFixed;
  }

  @Override
  public void writeProgress(Progress progress) {
  }

  @Override
  public void writeAccept(Accept accept) {
  }

  @Override
  public Progress readProgress(byte nodeIdentifier) {
    return new Progress(nodeIdentifier, promise, highestFixed);
  }

  @Override
  public Optional<Accept> readAccept(long logIndex) {
    return Optional.empty();
  }

  @Override
  public void sync() {
  }

  @Override
  public long highestLogIndex() {
    return 0;
  }
}
