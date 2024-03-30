package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@SuppressWarnings("unused")
public record Command(String msgUuid, byte[] values) implements AbstractCommand {

    @Override
    public void writeTo(DataOutputStream dataStream) throws IOException {
        dataStream.writeUTF(msgUuid);
        dataStream.writeInt(values.length);
        dataStream.write(values);
    }

    public static Command readFrom(DataInputStream dataInputStream) throws IOException {
        return new Command(dataInputStream.readUTF(), readByteArray(dataInputStream));
    }

    private static byte[] readByteArray(DataInputStream dataInputStream) throws IOException {
        byte[] bytes = new byte[dataInputStream.readInt()];
        dataInputStream.readFully(bytes);
        return bytes;
    }

    @Override
    public boolean equals(Object arg0) {
        if (this == arg0) {
            return true;
        }
        if (arg0 == null) {
            return false;
        }
        if (getClass() != arg0.getClass()) {
            return false;
        }
        Command other = (Command) arg0;
        if (msgUuid == null) {
            if (other.msgUuid != null) {
                return false;
            }
        } else if (!msgUuid.equals(other.msgUuid)) {
            return false;
        }
        if (values == null) {
            return other.values == null;
        } else {
            if (other.values == null) {
                return false;
            }
            if (values.length != other.values.length) {
                return false;
            }
            for (int i = 0; i < values.length; i++) {
                if (values[i] != other.values[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
