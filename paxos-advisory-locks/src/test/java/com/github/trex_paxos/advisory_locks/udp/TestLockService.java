package com.github.trex_paxos.advisory_locks.udp;

import com.github.trex_paxos.advisory_locks.LockHandle;
import com.github.trex_paxos.advisory_locks.TrexLockService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class TestLockService implements TrexLockService {
  final List<String> tryLockIds = new ArrayList<>();
  final Optional<LockHandle> mockHandle = Optional.of(new LockHandle(
      "test-lock",
      1L,
      Instant.now().plus(Duration.ofMinutes(5))
  ));


  @Override
  public Optional<LockHandle> tryLock(String id, Instant expiryTime) {
    tryLockIds.add(id);
    return mockHandle;
  }

  @Override
  public long expireTimeUnsafe(LockHandle lock) {
    return mockHandle.get().expireTimeWithSafetyGap().toEpochMilli();
  }

  @Override
  public Instant expireTimeWithSafetyGap(LockHandle lock, Duration safetyGap) {
    return mockHandle.get().expireTimeWithSafetyGap().plus(safetyGap);
  }

  @Override
  public boolean releaseLock(LockHandle lock) {
    return true;
  }

}
