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

/// Protocol constants and validation for Paxe secure network communication.
/// Encapsulates wire format knowledge and validation logic.
public sealed interface PaxeProtocol permits PaxePacket {

  int HEADER_SIZE = 8;

  // Flags byte structure
  int FLAGS_OFFSET = HEADER_SIZE;
  byte FLAG_DEK = 0x01;        // bit 0: DEK encryption
  byte FLAG_MAGIC_0 = 0x02;    // bit 1: must be 0
  byte FLAG_MAGIC_1 = 0x04;    // bit 2: must be 1

  // GCM parameters
  int GCM_NONCE_LENGTH = 12;
  int GCM_TAG_LENGTH = 16;
  int GCM_TAG_LENGTH_BITS = 128;

  // Protocol sizing
  int MAX_UDP_SIZE = 65507;
  int MIN_MESSAGE_SIZE = HEADER_SIZE + 1; // Header + flags
  int DEK_THRESHOLD = 64;
  int DEK_KEY_SIZE = 16;
}
