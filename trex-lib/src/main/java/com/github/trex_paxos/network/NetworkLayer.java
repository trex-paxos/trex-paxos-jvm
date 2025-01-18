package com.github.trex_paxos.network;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface NetworkLayer extends AutoCloseable {
  <T> void subscribe(Channel channel, Consumer<T> handler, String name);
  <T> void send(Channel channel, short to, T msg);
  <T> void broadcast(Supplier<ClusterMembership> membershipSupplier, Channel channel, T msg);
  void start();
  void stop() throws Exception;
}
