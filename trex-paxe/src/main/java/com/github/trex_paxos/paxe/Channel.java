package com.github.trex_paxos.paxe;

public record Channel(byte value) {
    public static final byte CONSENSUS = 0;
    
    public Channel {
        if (value < 0) throw new IllegalArgumentException("Channel must be non-negative");
    }
}