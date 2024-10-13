package com.github.trex_paxos.msg;

import java.io.DataOutputStream;
import java.io.IOException;

/// There are two types of commands. The NOOP which is used to speed up recovery and real commands sent by clients of the host applicationd
public sealed interface AbstractCommand extends Message permits NoOperation, Command {
    void writeTo(DataOutputStream dataStream) throws IOException;
}
