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
