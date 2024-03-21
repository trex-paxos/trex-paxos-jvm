package com.github.trex_paxos;

import java.util.Map;

record AcceptResponsesAndTimeout(Long timeout, Accept accept, Map<Integer, AcceptResponse> responses){}

