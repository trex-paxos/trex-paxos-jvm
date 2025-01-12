package com.github.trex_paxos;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class Timeouts {
  final static SecureRandom random = new SecureRandom();

  @SuppressWarnings("unused")
  public static void main(String[] args) {
    final var iterations = 1000;
    final var max = 10;
    IntStream.range(2, 10).forEach(multiplier -> {
      AtomicInteger countGood = new AtomicInteger();
      AtomicInteger countBad = new AtomicInteger();
      IntStream.range(0, iterations).forEach(_ -> {
        final var first = random.nextInt(multiplier * max);
        final var second = random.nextInt(multiplier * max);
        if (Math.abs(first - second) > max) {
          countGood.getAndIncrement();
        } else {
          countBad.getAndIncrement();
        }
      });
      System.out.println(multiplier + " - Good: " + countGood.get() + " Bad: " + countBad.get() + " Good Ratio: " + (double) countGood.get() / iterations);
    });

    double exactMultiplier = 3.6;
    AtomicInteger countGood = new AtomicInteger();
    AtomicInteger countBad = new AtomicInteger();
    IntStream.range(0, iterations).forEach(_ -> {
      final var first = random.nextInt((int) (exactMultiplier * max));
      final var second = random.nextInt((int) (exactMultiplier * max));
      if (Math.abs(first - second) > max) {
        countGood.getAndIncrement();
      } else {
        countBad.getAndIncrement();
      }
    });
    System.out.println(exactMultiplier + " - Good: " + countGood.get() + " Bad: " + countBad.get() + " Good Ratio: " + (double) countGood.get() / iterations);
  }
}
