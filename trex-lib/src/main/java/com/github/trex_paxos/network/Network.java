package com.github.trex_paxos.network;

public interface Network extends AutoCloseable {
    void send(Message message);
    void subscribe(MessageHandler handler);
    
    record Message(Channel channel, NodeId from, NodeId to, byte[] payload) {}
    interface MessageHandler {
        void onMessage(Message message);
    }
}