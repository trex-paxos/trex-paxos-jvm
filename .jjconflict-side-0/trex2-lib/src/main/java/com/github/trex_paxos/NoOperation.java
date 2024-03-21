package com.github.trex_paxos;

import java.io.DataOutputStream;

@SuppressWarnings("unused")
public final class NoOperation implements AbstractCommand, PaxosMessage {

    @Override
    public void writeTo(DataOutputStream dataStream) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'writeTo'");
    }

}
