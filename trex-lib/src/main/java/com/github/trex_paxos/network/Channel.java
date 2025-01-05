package com.github.trex_paxos.network;

public record Channel(short value) {
  public enum SystemChannel {
    CONSENSUS((short) 0),       // Core paxos consensus
    KEY_EXCHANGE((short) 1),    // Initial key exchange/auth
    PROXY((short) 2);          // Forward commands to leader

    private final short value;

    SystemChannel(short value) {
      this.value = value;
    }

    public Channel asChannel() {
      return new Channel(value);
    }

  }

  public static String getSystemChannelName(short value) {
    for (SystemChannel channel : SystemChannel.values()) {
      if (channel.value == value) {
        return channel.name();
      }
    }
    return "Channel(" + value + ")";
  }

  @Override
  public String toString() {
    return getSystemChannelName(value);
  }

  public static final Channel CONSENSUS = SystemChannel.CONSENSUS.asChannel();
  @SuppressWarnings("unused")
  public static final Channel KEY_EXCHANGE = SystemChannel.KEY_EXCHANGE.asChannel();
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
