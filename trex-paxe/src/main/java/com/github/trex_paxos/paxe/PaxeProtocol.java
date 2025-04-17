// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
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
