package com.github.trex_paxos;

/**
 * A callback for when a value is committed.
 */
@FunctionalInterface
public interface UpCall {
  void committed(long t);
}
