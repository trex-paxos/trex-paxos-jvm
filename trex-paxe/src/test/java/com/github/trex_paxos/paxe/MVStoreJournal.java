package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Journal;
import com.github.trex_paxos.Progress;
import com.github.trex_paxos.msg.Accept;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.util.Optional;

public class MVStoreJournal implements Journal {
  private final MVStore store;
  private final MVMap<Long, Accept> accepts;
  private final MVMap<Short, Progress> progress;

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
  public Progress readProgress(short nodeIdentifier) {
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
