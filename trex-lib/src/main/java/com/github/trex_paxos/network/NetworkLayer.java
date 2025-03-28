package com.github.trex_paxos.network;

import java.util.function.Consumer;
import java.util.function.Supplier;

/// Trex is agnostic to the underlying network layer. This interface abstracts the network layer.
public interface NetworkLayer extends AutoCloseable {
  <T> void subscribe(Channel channel, Consumer<T> handler, String name);
  <T> void send(Channel channel, NodeId to, T msg);
  <T> void broadcast(Supplier<ClusterEndpoint> membershipSupplier, Channel channel, T msg);
  void start();
}
