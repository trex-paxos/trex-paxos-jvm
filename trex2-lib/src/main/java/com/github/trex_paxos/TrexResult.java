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

import com.github.trex_paxos.msg.AbstractCommand;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// The result of running paxos is a list of messages and a list of commands.
///
/// @param messages A possibly empty list of messages that were generated during the paxos run.
/// @param commands A possibly empty list of chosen aka fixed commands.
public record TrexResult(List<TrexMessage> messages, Map<Long, AbstractCommand> commands) {
  public TrexResult {
    messages = List.copyOf(messages);
    commands = Map.copyOf(commands);
  }
  static TrexResult noResult() {
    return new TrexResult(List.of(), Map.of());
  }

  static TrexResult merge(List<TrexResult> results) {
    if (results.isEmpty()) {
      return noResult();
    } else if (results.size() == 1) {
      return results.getFirst();
    }
    final var allMessages = results.stream().flatMap(r -> r.messages().stream()).toList();
    final var allCommands = results.stream()
        .flatMap(r -> r.commands().entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            // paxos gives unique commands at each slot we assert that is the case below.
            (v, _) -> v,
            TreeMap::new // Use TreeMap as the map supplier
        ));

    // Check that the size of unique key-value pairs of the inputs matches the size of allCommands
    // If this is not the case then we manged to fix different commands at the same slot.
    assert allCommands.size() == results.stream()
        .flatMap(r -> r.commands().entrySet().stream())
        .collect(Collectors.toSet()).size();

    return new TrexResult(allMessages, allCommands);
  }
}
