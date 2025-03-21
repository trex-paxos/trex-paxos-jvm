/*
 * Copyright 2024 Simon Massey
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

/// There are two primary types of results. The NOOP which is used to speed up recovery and normal results sent by clients of
/// the host application.
///
/// At a future date we are likely to add some cluster reconfiguration results. These will be used to add and remove
/// nodes from the cluster or to change voting weights.
public sealed interface AbstractCommand permits NoOperation, Command {
}
