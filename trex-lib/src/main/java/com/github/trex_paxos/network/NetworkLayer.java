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
package com.github.trex_paxos.network;

import com.github.trex_paxos.NodeId;

import java.util.function.Consumer;
import java.util.function.Supplier;

/// Trex is agnostic to the underlying network layer. This interface abstracts the network layer.
public interface NetworkLayer extends AutoCloseable {
  <T> void subscribe(Channel channel, Consumer<T> handler, String name);
  <T> void send(Channel channel, NodeId to, T msg);
  <T> void broadcast(Supplier<NodeEndpoint> membershipSupplier, Channel channel, T msg);
  void start();
}
