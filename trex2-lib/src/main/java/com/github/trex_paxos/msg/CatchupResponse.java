package com.github.trex_paxos.msg;

import java.util.List;
/// CatchupResponse is a message sent by the leader to a replica in response to a Catchup message.
/// It will only return committed slots that the replica has requested. This is to avoid sending
/// lots of uncommitted messages during a partition where an old leader is not yet aware of a new leader.
public record CatchupResponse(byte from,
                              byte to,
                              List<Accept> catchup,
                              Commit commit
) implements TrexMessage, DirectMessage {
}
