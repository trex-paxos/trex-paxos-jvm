package com.github.trex_paxos.network;

public record Channel(short id) {
  
  // Application channels start from 100 to avoid collisions
  @SuppressWarnings("unused")
  public static Channel applicationChannel(short value) {
    if (value < 100) {
      throw new IllegalArgumentException("Application channel values must be >= 100");
    }
    return new Channel(value);
  }
}
