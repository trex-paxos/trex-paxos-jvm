package com.github.trex_paxos.msg;

import java.io.DataOutputStream;

public final class NoOperation implements AbstractCommand {

    public final static NoOperation NOOP = new NoOperation();

    @Override
    public void writeTo(DataOutputStream dataStream) {
        // do nothing. whatever is writing us will use a sentinel value to indicate no operation
    }

  /// Here all NoOperation instances are equal to each other.
  @Override
  public boolean equals(Object obj) {
    return super.equals(obj) || obj instanceof NoOperation;
  }

  @Override
  public String toString() {
    return "NOOP";
  }
}
