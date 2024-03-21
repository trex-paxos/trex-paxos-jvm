package com.github.trex_paxos;

import java.io.*;

public class Pickle {
    public static PaxosMessage read(byte[] bytes) {
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream dis = new DataInputStream(bis);
        try {
            CommandType commandType = CommandType.fromId(dis.readByte());
            switch (commandType) {
                case CommandType.Prepare:
                    return Prepare.readFrom(dis);
                case CommandType.PrepareAck:
                    return PrepareAck.readFrom(dis);
                case CommandType.PrepareNack:
                    return PrepareNack.readFrom(dis);
                case CommandType.Accept:
                    return Accept.readFrom(dis);
                case CommandType.AcceptAck:
                    return AcceptAck.readFrom(dis);
                case CommandType.AcceptNack:
                    return AcceptNack.readFrom(dis);
                case CommandType.Commit:
                    return Commit.readFrom(dis);
                default:
                    throw new AssertionError("Unknown command type: " + commandType);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] write(PaxosMessage message) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
        dos.writeByte(CommandType.fromPaxosMessage(message).id());
        message.writeTo(dos);
        dos.close();
        return byteArrayOutputStream.toByteArray();
    }
}
