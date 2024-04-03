package com.github.trex_paxos;

public sealed interface AcceptResponse extends TrexMessage permits AcceptAck, AcceptNack {
    /**
    * @return the highest committed identifier in the progress of the responder
    */
    Identifier highestCommitted();

    /**
     * @return the request identifier of the request that this response is for
     */
    Identifier requestId();

    /**
     * @return the proposer that sent the request
     */
    int from();

    /**
     * @return the progress of the responder
     */
    Progress progress();
}
