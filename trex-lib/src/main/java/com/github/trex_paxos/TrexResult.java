/*
 * Copyright 2024 Simon Massey
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

import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// The result of running the paxos algorithm for a batch of input messages is a possible empty list of sequentially
/// fixed commands results and a possibly empty list of messages to be sent out. The journal must be made crash durable
/// before any messages are sent out see [Journal].
///
/// @param results A possibly empty list of sequentially chosen commands aka fixed values.
/// @param messages A possibly empty list of messages that were generated to be sent out after the journal is made crash durable.
 public record TrexResult(List<TrexMessage> messages, TreeMap<Long, AbstractCommand> results) {
  public TrexResult {
    messages = List.copyOf(messages);
    results = new TreeMap<>(results);
  }

  static TrexResult noResult() {
    return new TrexResult(List.of(), new TreeMap<>());
  }

  public Collection<Object> fixed() {
    return results().isEmpty() ? List.of() :
        results()
            .values()
            .stream()
            .filter(c -> c instanceof Command)
            .collect(Collectors.toList());
  }
}
