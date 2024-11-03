package com.github.trex_paxos.msg;

import java.util.List;

public record CatchupResponse(byte from, byte to,
                              List<Accept> catchup) implements TrexMessage, DirectMessage {

}
