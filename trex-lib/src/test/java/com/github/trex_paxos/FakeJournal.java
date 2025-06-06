// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;

import java.util.Optional;

public class FakeJournal implements Journal {
  private final BallotNumber promise;
  private final long highestFixed;

  public FakeJournal(BallotNumber promise, long highestFixed) {
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
  public Progress readProgress(short nodeIdentifier) {
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
