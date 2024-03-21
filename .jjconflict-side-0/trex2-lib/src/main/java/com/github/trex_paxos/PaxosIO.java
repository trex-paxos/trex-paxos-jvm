package com.github.trex_paxos;

import java.util.function.Consumer;

public class PaxosIO {

    public Consumer<JournalRecord> journal() {
        throw new AssertionError("Not implemented");
    }

    /**
     * Send an AcceptAck acknowledge message to the leader.
     * @param accept The accept message to acknowledge.
     */
    public void ack(Accept accept) {
        throw new AssertionError("Not implemented");
    }

    /**
     * Send an AcceptNack negative acknowledgement message to the leader.
     * @param accept The accept message to reject.
     */
    public void nack(Accept accept) {
        throw new AssertionError("Not implemented");
    }
}
