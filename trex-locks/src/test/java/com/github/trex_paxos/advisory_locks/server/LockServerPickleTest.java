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

import com.github.trex_paxos.advisory_locks.store.LockStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class LockServerPickleTest {

  @Test
  void shouldPickleAndUnpickleTryAcquireLock() {
    LockServerCommandValue.TryAcquireLock original = new LockServerCommandValue.TryAcquireLock(
        "test-lock",
        LockStore.expiryTimeWithSafetyGap(Duration.ofSeconds(30), Duration.ofSeconds(1))
    );

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerCommandValue unpickled = LockServerPickle.unpickleCommand(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleReleaseLock() {
    LockServerCommandValue.ReleaseLock original = new LockServerCommandValue.ReleaseLock(
        "test-lock",
        123L
    );

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerCommandValue unpickled = LockServerPickle.unpickleCommand(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleGetLock() {
    LockServerCommandValue.GetLock original = new LockServerCommandValue.GetLock("test-lock");

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerCommandValue unpickled = LockServerPickle.unpickleCommand(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleTryAcquireLockReturn() {
    LockStore.LockEntry entry = new LockStore.LockEntry(
        "test-lock",
        123L,
        Instant.now(),
        System.currentTimeMillis()
    );
    LockServerReturnValue.TryAcquireLockReturn original =
        new LockServerReturnValue.TryAcquireLockReturn(Optional.of(entry));

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerReturnValue unpickled = LockServerPickle.unpickleReturn(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleEmptyTryAcquireLockReturn() {
    LockServerReturnValue.TryAcquireLockReturn original =
        new LockServerReturnValue.TryAcquireLockReturn(Optional.empty());

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerReturnValue unpickled = LockServerPickle.unpickleReturn(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleReleaseLockReturn() {
    LockServerReturnValue.ReleaseLockReturn original =
        new LockServerReturnValue.ReleaseLockReturn(true);

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerReturnValue unpickled = LockServerPickle.unpickleReturn(pickled);

    assertThat(unpickled).isEqualTo(original);
  }

  @Test
  void shouldPickleAndUnpickleGetLockReturn() {
    LockStore.LockEntry entry = new LockStore.LockEntry(
        "test-lock",
        123L,
        Instant.now(),
        System.currentTimeMillis()
    );
    LockServerReturnValue.GetLockReturn original =
        new LockServerReturnValue.GetLockReturn(Optional.of(entry));

    byte[] pickled = LockServerPickle.pickle(original);
    LockServerReturnValue unpickled = LockServerPickle.unpickleReturn(pickled);

    assertThat(unpickled).isEqualTo(original);
  }
}
