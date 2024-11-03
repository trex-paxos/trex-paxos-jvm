package com.github.trex_paxos.msg;

/// There are two types of commands. The NOOP which is used to speed up recovery and real commands sent by clients of the host applicationd
public sealed interface AbstractCommand extends Message permits NoOperation, Command {

}
