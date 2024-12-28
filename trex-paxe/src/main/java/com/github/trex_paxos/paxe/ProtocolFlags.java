package com.github.trex_paxos.paxe;

// Protocol flags for header
public final class ProtocolFlags {
    public static final byte AUTH_REQUIRED  = (byte) 0x80;
    public static final byte IS_FRAGMENTED = 0x40;
    public static final byte FRAGMENT_START = 0x20;
    public static final byte FRAGMENT_END   = 0x10;
    
    private ProtocolFlags() {} // No instances
}
