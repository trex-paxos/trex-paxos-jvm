package com.github.trex_paxos;

/// This abstract class is a transport-agnostic network interface for Paxos message exchange.
/// The idea is that there could be TCP, UDP, QUIC, or other transport implementations.
/// It is assumed that we are on Java 22 or later such that you should look to use something friendly to Virtual Threads.
public interface Transport {
  record Frame(byte nodeIdentifier, byte[] payload) {}
  void send(Frame frame) throws Exception;
  Frame receive() throws Exception;
}
