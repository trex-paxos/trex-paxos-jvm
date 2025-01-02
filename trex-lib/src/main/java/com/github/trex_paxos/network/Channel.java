package com.github.trex_paxos.network;

public record Channel(short value) {
    public static final short CONSENSUS = 0;
    public static final short KEY_EXCHANGE = 255;
    public static final Channel CONSENSUS_CHANNEL = new Channel(CONSENSUS);
    public static final Channel KEY_EXCHANGE_CHANNEL = new Channel(KEY_EXCHANGE);
}