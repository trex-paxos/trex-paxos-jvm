package com.github.trex_paxos.msg;

import java.io.DataOutputStream;
import java.io.IOException;

public sealed interface AbstractCommand extends Message permits NoOperation, Command {
    void writeTo(DataOutputStream dataStream) throws IOException;
}
