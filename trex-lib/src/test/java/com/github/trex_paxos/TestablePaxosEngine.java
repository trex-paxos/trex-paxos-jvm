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

import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Level;

import static com.github.trex_paxos.TrexLogger.LOGGER;

abstract class TestablePaxosEngine<RESULT> extends TrexEngine<RESULT> {

  final TransparentJournal journal;

  public TestablePaxosEngine(
      short nodeIdentifier,
      QuorumStrategy quorumStrategy,
      TransparentJournal journal,
      BiFunction<Long, Command, RESULT> commitCallback
  ) {
    // Pass commitCallback to super constructor
    super(new TrexNode(Level.INFO, nodeIdentifier, quorumStrategy, journal), commitCallback);
    this.journal = journal;
  }

  @Override
  public EngineResult<RESULT> paxos(List<TrexMessage> input) {
    LOGGER.finer(() -> trexNode.nodeIdentifier + " <~ " + input);
    final var oldRole = trexNode.getRole();
    final var result = super.paxos(input);
    final var newRole = trexNode.getRole();
    if (oldRole != newRole) {
      LOGGER.info(() -> "Node has changed role:" + trexNode.nodeIdentifier() + " == " + newRole);
    }
    return result;
  }

  @Override
  public String toString() {
    return "TestablePaxosEngine{" +
        trexNode.nodeIdentifier() + "=" +
        trexNode.currentRole().toString() + "," +
        trexNode.progress +
        '}';
  }

  public String role() {
    return trexNode.currentRole().toString();
  }
}
