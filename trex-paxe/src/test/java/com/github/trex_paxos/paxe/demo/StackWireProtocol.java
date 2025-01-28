package com.github.trex_paxos.paxe.demo;

/**
 * Wire protocol for the distributed stack service.
 * Commands are replicated via Paxos to maintain stack state consistency.
 */
public interface StackWireProtocol {
  sealed interface Command permits Push, Pop, Peek {
  }

  record Push(String value) implements Command {
  }

  record Pop() implements Command {
  }

  record Peek() implements Command {
  }

  record Result(String value) {
  }
}
