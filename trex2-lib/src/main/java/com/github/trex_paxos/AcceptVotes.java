package com.github.trex_paxos;

import java.util.Collections;
import java.util.Map;

// TODO we must timeout on not hearing back any responses
record AcceptVotes(Accept accept, Map<Integer, AcceptResponse> responses, boolean chosen){
    static AcceptVotes chosen(Accept accept) {
        return new AcceptVotes(accept, Collections.emptyMap(), true);
    }
}

