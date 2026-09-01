// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
/// # PAXE Wire Protocol Implementation
///
/// PAXE (Paxos Encrypted) is a protected intracluster UDP datagram codec. Each cluster member holds
/// the same 32-byte pre-shared key per epoch, installed out of band. PAXE does not negotiate keys,
/// distribute verifiers, or derive pairwise session state.
///
/// ## Wire format
///
/// ```
/// Prefix(9) | Nonce(12) | Ciphertext | Tag(16)
/// ```
///
/// Prefix bytes: `BE16(fromId) || BE16(toId) || BE32(channel) || epoch`
///
/// The prefix is the exact AES-256-GCM associated data. `fromId` is authenticated routing metadata;
/// because every member holds the same cluster key it is not a distinct cryptographic node identity.
///
/// Plaintext length is derived from the UDP datagram length minus 37 bytes of fixed overhead.
///
/// Optional TLS 1.3 external-PSK provisioning may transport the cluster PSK, but that control-plane
/// connection is outside PAXE and must not derive a different per-node data key.
package com.github.trex_paxos.paxe;
