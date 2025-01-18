package com.github.trex_paxos.network;

public record Channel(short id) {
  public enum SystemChannel {
    CONSENSUS((short) 1),       // Core paxos consensus
    PROXY((short) 2);          // Forward commands to leader

    private final short id;

    SystemChannel(short id) {
      this.id = id;
    }

    public Channel asChannel() {
      return new Channel(id);
    }

  }

  public static String getSystemChannelName(short id) {
    for (SystemChannel channel : SystemChannel.values()) {
      if (channel.id == id) {
        return channel.name();
      }
    }
    return "Channel(" + id + ")";
  }

  @Override
  public String toString() {
    return getSystemChannelName(id);
  }

  public static final Channel CONSENSUS = SystemChannel.CONSENSUS.asChannel();
  public static final Channel PROXY = SystemChannel.PROXY.asChannel();

  // Application channels start from 100 to avoid collisions
  @SuppressWarnings("unused")
  public static Channel applicationChannel(short value) {
    if (value < 100) {
      throw new IllegalArgumentException("Application channel values must be >= 100");
    }
    return new Channel(value);
  }
}
