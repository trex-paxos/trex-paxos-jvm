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
/// The network package provides transport abstractions for the Paxos implementation.
///
/// Key interfaces:
/// - `TrexNetwork`: Core interface for sending/receiving messages between nodes
/// - `Channel`: Defines CONSENSUS and PROXY channels
/// - `NodeId`: Unique node identifier within cluster
/// - `NetworkAddress`: TrexNetwork location abstraction
/// - `NodeEndpoint`: Cluster topology management
///
/// Design characteristics:
/// 1. Only COMMAND objects are sent between nodes
/// 2. RESULT objects are computed locally at each node
/// 3. Transport agnostic to support different network implementations
package com.github.trex_paxos.network;
