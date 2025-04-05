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
package com.github.trex_paxos.paxe;

import java.util.Objects;

/// The RFC5054 SRP verifier for a node within a particular  cluster
/// @param identity RFC 5054 identity which is the node id and cluster id concatenated with '@' such as "1@test.cluster"
/// @param verifier RFC 5054 verifier as hex string
public record NodeVerifier(
  String identity,
  String verifier
) {
  public NodeVerifier {
    Objects.requireNonNull(identity, "identity required");
    Objects.requireNonNull(verifier, "verifier required");
  }
}
