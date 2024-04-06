package com.github.trex_paxos;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

public class LossyJournal implements Journal {

  final Map<Byte, Progress> progressMap = new HashMap<>();

  @Override
  public void saveProgress(Progress progress) {
    progressMap.put(progress.nodeIdentifier(), progress);
  }

  @Override
  public Progress loadProgress(byte nodeIdentifier) {
    return progressMap.get(nodeIdentifier);
  }

  final NavigableMap<Long, Accept> acceptMap = new java.util.TreeMap<>();

  @Override
  public void journalAccept(Accept accept) {
    acceptMap.put(accept.id().logIndex(), accept);
  }

  @Override
  public Optional<Accept> loadAccept(long logIndex) {
    return Optional.ofNullable(acceptMap.get(logIndex));
  }

  final Map<Byte, Long> committedMap = new HashMap<>();

  @Override
  public void committed(byte nodeIdentifier, long l) {
    committedMap.put(nodeIdentifier, l);
  }
}
