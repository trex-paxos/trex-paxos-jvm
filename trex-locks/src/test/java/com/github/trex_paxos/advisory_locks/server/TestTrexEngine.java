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

import com.github.trex_paxos.Progress;
import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.TrexNode;
import com.github.trex_paxos.msg.Prepare;

import java.util.Optional;

public class TestTrexEngine extends TrexEngine {
  public Optional<Prepare> timeoutForTest() {
    return timeout();
  }

  public TestTrexEngine(TrexNode trexNode) {
    super(trexNode);
  }

  @Override
  protected void setRandomTimeout() {
  }

  @Override
  protected void clearTimeout() {
  }

  @Override
  protected void setNextHeartbeat() {
  }

  public Progress getProgress() {
    return trexNode().progress();
  }
}
