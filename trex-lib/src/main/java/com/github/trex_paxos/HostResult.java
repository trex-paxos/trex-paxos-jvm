// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.util.UUID;

/// When a Command value is fixed the upCall is run to the host application to get back a result. The result is
/// correlated with client uuid so that the result can be routed back to the originating client. The slot is also
/// returned which provides a unique ordering to the results. As this is globally unique across all clients and all
/// time. It is returned as it maybe be used for application purposes. For example, it can be used as the stamped-lock
/// for a Paxos based distributed advisory lock.
public record HostResult<R>(long slot, UUID uuid, R result) {
}
