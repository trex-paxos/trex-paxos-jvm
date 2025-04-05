/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
