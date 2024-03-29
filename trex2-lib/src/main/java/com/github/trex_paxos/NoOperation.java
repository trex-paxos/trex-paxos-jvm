package com.github.trex_paxos;

import java.io.DataOutputStream;

@SuppressWarnings("unused")
public final class NoOperation implements AbstractCommand, PaxosMessage {

    public final static NoOperation NOOP = new NoOperation();

    @Override
    public void writeTo(DataOutputStream dataStream) {
        // do nothing. whatever is writing us will use a sentinel value to indicate no operation
    }

}
