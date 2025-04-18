// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.util.Optional;

// @formatter:off
@SuppressWarnings("UnusedReturnValue") public interface StackService {
  sealed interface Value permits Push, Pop, Peek {}
  record Push(String item) implements Value {}
  record Pop() implements Value {}
  record Peek() implements Value {}
  sealed interface Response permits Success, Failure {
    static Response success(String value) {
      return new Success(Optional.ofNullable(value));
    }
    static Response failure(Throwable problem) {
      return new Failure(problem.getMessage());
    }
    String payload();
  }
  record Success(Optional<String> value) implements Response {
    public String payload() {
      return value.orElse(null);
    }
  }
  record Failure(String errorMessage) implements Response {
    public String payload() {
      return errorMessage;
    }
  }
  Response push(String item);
  Response pop();
  Response peek();

}
// @formatter:on
