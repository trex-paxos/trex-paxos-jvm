// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

/// Protocol constants and validation for cluster-PSK PAXE datagrams.
public sealed interface PaxeProtocol permits PaxePacket {

  int PREFIX_SIZE = 9;
  int GCM_NONCE_LENGTH = 12;
  int GCM_TAG_LENGTH = 16;
  int GCM_TAG_LENGTH_BITS = 128;
  int FRAME_OVERHEAD = PREFIX_SIZE + GCM_NONCE_LENGTH + GCM_TAG_LENGTH;
  int MAX_UDP_SIZE = 65507;
  int MAX_PLAINTEXT_SIZE = MAX_UDP_SIZE - FRAME_OVERHEAD;
}
