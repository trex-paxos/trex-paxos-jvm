// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import com.github.trex_paxos.Legislators;
import com.github.trex_paxos.NodeId;

import java.io.Closeable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Trex is agnostic to the underlying network layer. This interface abstracts the network layer.
public interface NetworkLayer extends Closeable {
  <T> void subscribe(Channel channel, Consumer<T> handler, String name);
  <T> void send(Channel channel, NodeId to, T msg);
  <T> void broadcast(Supplier<Legislators> membershipSupplier, Channel channel, T msg);
  void start();
}
