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
