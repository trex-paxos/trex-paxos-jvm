package com.github.trex_paxos.demo;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/// The core java UUID generator class can can only create weak Type 3 and strong Type 4 random UUIDs.
/// Here we use the current time in milliseconds and a counter in the most significant bits with a secure random
/// in the lowest bits. This gives us good time based ordering within a single JVM. Across multiple JVMs it will be
/// biased towards the JVM with the most recent time and highest counter. We initialize the counter to the startup
/// time which may favour the machine which was started last. This is a good trade-off for our use case.
///
/// The RFC for time based UUIDs suggest that 10M UUIDs per second can be generated. Testing the Java core Type 4
/// UUID generation it gets about 0.6M per second. This class gets about 0.5M per second.
public class UUIDGenerator {
  /// A trick from the core UUID class is to use holder class to defer initialization until needed.
  private static class LazyRandom {
    static final SecureRandom RANDOM = new SecureRandom();
  }

  private static class LazyCounter {
    // This counter starts off biased towards the server which started last.
    private static final AtomicLong sequence = new AtomicLong(System.currentTimeMillis());
  }

  /// This takes the Unix/Java epoch time in milliseconds, bit shifts it left by 20 bits, and then masks in the least
  /// significant 20 bits of the local counter.
  public static long uniqueTimestamp() {
    long currentMillis = System.currentTimeMillis();
    // Take the least significant 20 bits from our atomic sequence
    long subMillis = LazyCounter.sequence.incrementAndGet() & 0xFFFFF;
    return (currentMillis << 20) | subMillis;
  }

  public static UUID generateUUID() {
    // Get the most significant bits
    long msb = uniqueTimestamp();
    long lsb = LazyRandom.RANDOM.nextLong();
    return new UUID(msb, lsb);
  }
}
