package com.github.trex_paxos.paxe;

import java.io.Serializable;
import java.util.Optional;

public interface StackService {
  sealed interface Command extends Serializable {
  }

  record Push(String item) implements Command {
  }

  record Pop() implements Command {
  }

  record Peek() implements Command {
  }

  record Response(Optional<String> value) {
  }

}
