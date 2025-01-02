package com.github.trex_paxos.paxe;

public record Channel(byte value) {
    public static final byte CONSENSUS = 0;
    public static final byte KEY_EXCHANGE = (byte)255;
    static final Channel CONSENSUS_CHANNEL = new Channel(CONSENSUS);
    static final Channel KEY_EXCHANGE_CHANNEL = new Channel(KEY_EXCHANGE);
}