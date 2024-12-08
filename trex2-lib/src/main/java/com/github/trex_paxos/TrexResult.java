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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// The result of running the paxos algorithm for an input messages is a possible empty list of sequentially fixed
/// commands and a possibly empty list of messages to be sent out. The journal must be made crash durable before any
/// messages are sent out see [Journal].
///
/// @param commands A possibly empty list of sequentially chosen values aka fixed commands for the host application to process.
/// @param messages A possibly empty list of messages that were generated to be sent out after the journal is made crash durable.
public record TrexResult(List<TrexMessage> messages, TreeMap<Long, AbstractCommand> commands) {
  public TrexResult {
    messages = List.copyOf(messages);
    commands = new TreeMap<>(commands);
  }
  static TrexResult noResult() {
    return new TrexResult(List.of(), new TreeMap<>());
  }

  /// In oder to support the batching of many messages into network packet this method is provided to merge the results.
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

  public Collection<Object> fixed() {
    return commands().isEmpty() ? List.of() :
        commands()
            .values()
            .stream()
            .filter(c -> c instanceof Command)
            .collect(Collectors.toList());
  }
}
