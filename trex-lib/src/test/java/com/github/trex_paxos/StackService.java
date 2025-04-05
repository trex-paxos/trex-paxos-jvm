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
