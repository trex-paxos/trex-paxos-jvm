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
package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.Journal;
import com.github.trex_paxos.Progress;
import com.github.trex_paxos.msg.Accept;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVMap;

import java.util.Optional;

public class MVStoreJournal implements Journal {
  private final MVStore store;
  private final MVMap<Long, Accept> accepts;
  private final MVMap<Byte, Progress> progress;

  public MVStoreJournal(MVStore store) {
    this.store = store;
    this.accepts = store.openMap("com.github.trex_paxos.advisory_locks.server#accepts");
    this.progress = store.openMap("com.github.trex_paxos.advisory_locks.server#progress");
  }

  @Override
  public void writeProgress(Progress progress) {
    this.progress.put(progress.nodeIdentifier(), progress);
  }

  @Override
  public void writeAccept(Accept accept) {
    accepts.put(accept.slot(), accept);
  }

  @Override
  public Progress readProgress(byte nodeIdentifier) {
    return progress.get(nodeIdentifier);
  }

  @Override
  public Optional<Accept> readAccept(long logIndex) {
    return Optional.ofNullable(accepts.get(logIndex));
  }

  @Override
  public void sync() {
    store.commit();
  }

  @Override
  public long highestLogIndex() {
    return accepts.isEmpty() ? 0 : accepts.lastKey();
  }
}
