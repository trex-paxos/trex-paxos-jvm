package com.github.trex_paxos.msg;

public sealed interface Message permits TrexMessage, AbstractCommand {
}
