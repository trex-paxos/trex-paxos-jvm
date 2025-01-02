package com.github.trex_paxos.paxe;

import java.util.Arrays;
import java.util.Objects;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.NodeId;

public record PaxeMessage(
    NodeId from,
    NodeId to,
    Channel channel,
    byte[] payload
) {
    public PaxeMessage {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
    }

    public byte[] serialize() {
        return payload;
    }
    
    public static PaxeMessage deserialize(NodeId from, NodeId to, Channel channel, byte[] payload) {
        return new PaxeMessage(from, to, channel, payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaxeMessage that)) return false;
        return from.equals(that.from)
                && to.equals(that.to)
                && channel.equals(that.channel)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(from, to, channel);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}