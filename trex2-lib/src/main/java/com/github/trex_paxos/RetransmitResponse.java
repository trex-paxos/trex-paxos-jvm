package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record RetransmitResponse(byte from, byte to, Accept[] accepts, Commit commit) implements TrexMessage {
    public static RetransmitResponse readFrom(DataInputStream dis) throws IOException {
        byte from = dis.readByte();
        byte to = dis.readByte();
        Accept[] accepts = new Accept[dis.readByte()];
        for (int i = 0; i < accepts.length; i++) {
            accepts[i] = Accept.readFrom(dis);
        }
        Commit commit = Commit.readFrom(dis);
        return new RetransmitResponse(from, to, accepts, commit);
    }

    @Override
    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeByte(from);
        dos.writeByte(to);
        dos.writeByte(accepts.length);
        for (Accept accept : accepts) {
            accept.writeTo(dos);
        }
        commit.writeTo(dos);
    }
}
