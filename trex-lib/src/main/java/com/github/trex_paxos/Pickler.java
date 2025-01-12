package com.github.trex_paxos;

public interface Pickler<T> {
  byte[] serialize(T cmd);

  T deserialize(byte[] bytes);
}
