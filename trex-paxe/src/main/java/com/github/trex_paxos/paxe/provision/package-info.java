// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
/// TLS 1.3 control-plane provisioning for cluster PSK distribution.
///
/// The bootstrap external PSK authenticates the provisioning channel only. The same 32-byte cluster
/// data key for a requested epoch is delivered to every member and installed into
/// {@link com.github.trex_paxos.paxe.ClusterKeyManager}. This package never places TLS payloads on
/// PAXE UDP channels.
package com.github.trex_paxos.paxe.provision;
