package com.github.trex_paxos;


public interface Messaging {

    /**
     * Send an AcceptAck acknowledge message to the leader.
     * @param accept The accept message to acknowledge.
     */
    void ack(Accept accept);

    /**
     * Send an AcceptNack negative acknowledgement message to the leader.
     * @param accept The accept message to reject.
     */
    void nack(Accept accept);

}
