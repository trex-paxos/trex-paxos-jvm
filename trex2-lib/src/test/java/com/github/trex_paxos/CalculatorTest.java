package com.github.trex_paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {


  @BeforeEach
  void setUp() {
  }

  @Test
  void testAdd() {
    double result = 1 + 2;
    assertEquals(3, result);
  }


}
