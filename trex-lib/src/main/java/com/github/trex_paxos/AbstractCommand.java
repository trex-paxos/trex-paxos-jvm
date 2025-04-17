// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

/// There are two primary types of results:
/// - The [NoOperation] which is used to speed up recovery.
/// - [Command] which are normal commands. These come in flavors so that there can be system commands to reconfigure the cluster.
public sealed interface AbstractCommand permits NoOperation, Command {
}
