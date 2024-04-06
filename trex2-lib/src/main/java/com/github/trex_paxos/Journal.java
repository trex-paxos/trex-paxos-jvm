package com.github.trex_paxos;

import java.util.Optional;

public interface Journal {
  void saveProgress(Progress progress);

  void journalAccept(Accept accept);

  Progress loadProgress(byte nodeIdentifier);

  Optional<Accept> loadAccept(long slot);

  void commit(long l);
}
