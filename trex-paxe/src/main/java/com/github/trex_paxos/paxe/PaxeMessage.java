package com.github.trex_paxos.paxe;

public interface PaxeMessage {
    Channel channel();
    byte[] serialize();
    NodeId to();
    NodeId from();
    static PaxeMessage deserialize(byte[] decrypted) {
        throw new AssertionError("Unimplemented method 'deserialize'");
    }
}