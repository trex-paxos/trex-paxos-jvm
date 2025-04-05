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

public record Identity(short nodeId, String cluster) {
  public String full() {
    return nodeId + "@" + cluster;
  }

  public static Identity from(String id) {
    var parts = id.split("@", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("Identity must be in format nodeId@cluster");
    }
    try {
      return new Identity(Short.parseShort(parts[0]), parts[1]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid nodeId as cannot parse as a short: " + parts[0]);
    }
  }

}
