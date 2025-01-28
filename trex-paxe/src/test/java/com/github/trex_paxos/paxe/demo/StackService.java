package com.github.trex_paxos.paxe.demo;

/**
 * Client interface for interacting with the distributed stack.
 */
public interface StackService {
  /**
   * Push a value onto the stack.
   * @param value Value to push
   * @return The value that was pushed
   */
  String push(String value);

  /**
   * Pop the top value from the stack.
   * @return The value that was popped
   * @throws java.util.EmptyStackException if stack is empty
   */
  String pop();

  /**
   * Peek at the top value without removing it.
   * @return The value at the top of the stack
   * @throws java.util.EmptyStackException if stack is empty
   */
  String peek();
}
