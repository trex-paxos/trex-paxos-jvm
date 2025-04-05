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

import com.github.trex_paxos.NodeId;
/// This is an Secure Remote Password secret for a node see RFC5054
public record NodeClientSecret(
  String srpIdentity,
  String password,
  byte[] salt  // 16 bytes required
) {
  public NodeClientSecret {
    Objects.requireNonNull(srpIdentity, "srpIdentity required");
    Objects.requireNonNull(password, "password required");
    Objects.requireNonNull(salt, "salt required");
    if(salt.length != 16) {
      throw new IllegalArgumentException("salt must be 16 bytes");
    }
  }
  public NodeClientSecret(ClusterId clusterId, NodeId id, String password, byte[] salt) {
    this(id.id() + "@" + clusterId.id(), password, salt);
  }
}
