// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.util.Optional;

// @formatter:off
@SuppressWarnings("UnusedReturnValue") public interface StackService {
  sealed interface Value permits Push, Pop, Peek {}
  record Push(String item) implements Value {}
  record Pop() implements Value {}
  record Peek() implements Value {}
  record Response(Optional<String> value) {}
  Response push(String item);
  Response pop();
  Response peek();
}
// @formatter:on
