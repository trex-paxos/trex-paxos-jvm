// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
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
