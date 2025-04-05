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
package com.github.trex_paxos;

import java.util.UUID;

/// When a Command value is fixed the upCall is run to the host application to get back a result. The result is
/// correlated with client uuid so that the result can be routed back to the originating client. The slot is also
/// returned which provides a unique ordering to the results. As this is globally unique across all clients and all
/// time. It is returned as it maybe be used for application purposes. For example, it can be used as the stamped-lock
/// for a Paxos based distributed advisory lock.
public record HostResult<R>(long slot, UUID uuid, R result) {
}
