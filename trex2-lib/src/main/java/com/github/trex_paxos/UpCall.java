package com.github.trex_paxos;

@FunctionalInterface
public interface UpCall<T> {
  void committed(T t);
}
