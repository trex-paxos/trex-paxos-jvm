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

import java.time.Instant;

public sealed interface LockServerCommandValue {
  /// Try to acquire a lock with the given ID. If the lock is already held, this will fail.
  /// The stamp is not passed by the client rather it is generated by the server.
  record TryAcquireLock(
      String lockId,
      Instant expiryTime
  ) implements LockServerCommandValue {
  }

  record ReleaseLock(
      String lockId,
      long stamp
  ) implements LockServerCommandValue {
  }

  record GetLock(
      String lockId
  ) implements LockServerCommandValue {
  }
}
