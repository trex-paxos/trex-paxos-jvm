package com.github.trex_paxos;


import java.util.List;

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

    void ack(Prepare prepare);

  void nack(Prepare prepare, List<Accept> catchup);

    void prepare(Prepare p);

    void accept(Accept a);

  void send(TrexMessage retransmitRequest);
}
