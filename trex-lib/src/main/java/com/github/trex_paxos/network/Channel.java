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
package com.github.trex_paxos.network;

/// A channel is a short value that identifies the type of message being sent.
/// Channels below 100 are reserved for system messages
/// @see SystemChannel
public record Channel(short id) {
  
  // Application channels start from 100 to avoid collisions
  @SuppressWarnings("unused")
  public static Channel applicationChannel(short value) {
    if (value < 100) {
      throw new IllegalArgumentException("Application channel values must be >= 100");
    }
    return new Channel(value);
  }
}
