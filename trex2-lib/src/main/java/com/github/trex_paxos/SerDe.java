package com.github.trex_paxos;

public interface SerDe<T> {
  byte[] serialize(T cmd);

  T deserialize(byte[] bytes);
}
