/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.advisory_locks;

import org.junit.jupiter.api.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

public class TestRunner {
  public static void main(String[] args) throws ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException, InstantiationException {    // Check if the test class name is provided
    if (args.length == 0) {
      throw new IllegalArgumentException("Test class name is required");
    }
    String testClassName = args[0];

    Optional<String> methodToRun = args.length > 1 ? Optional.of(args[1]) : Optional.empty();

    Class<?> testClass = Class.forName(testClassName);

    // Collect methods by annotations
    Method beforeAllMethod = null;
    Method afterAllMethod = null;
    for (Method method : testClass.getDeclaredMethods()) {
      if (method.isAnnotationPresent(BeforeAll.class)) beforeAllMethod = method;
      if (method.isAnnotationPresent(AfterAll.class)) afterAllMethod = method;
    }

    Method[] beforeEachMethods = getAnnotatedMethods(testClass, BeforeEach.class);
    Method[] testMethods = getAnnotatedMethods(testClass, Test.class);
    Method[] afterEachMethods = getAnnotatedMethods(testClass, AfterEach.class);

    // Run @BeforeAll
    if (beforeAllMethod != null) {
      beforeAllMethod.setAccessible(true);
      beforeAllMethod.invoke(null); // Static method
    }

    // Run each @Test with @BeforeEach and @AfterEach
    for (Method testMethod : testMethods) {
      if (methodToRun.isPresent() && !testMethod.getName().equals(methodToRun.get())) {
        continue;
      }
      Object instance = testClass.getDeclaredConstructor().newInstance(); // Create new instance
      for (Method beforeEach : beforeEachMethods) {
        beforeEach.setAccessible(true);
        beforeEach.invoke(instance);
      }

      testMethod.setAccessible(true);
      try {
        testMethod.invoke(instance);
        System.out.println("Test passed: " + testMethod.getName());
      } catch (Exception e) {
        System.out.println("Test failed: " + testMethod.getName());
      }

      for (Method afterEach : afterEachMethods) {
        afterEach.setAccessible(true);
        afterEach.invoke(instance);
      }
    }

    // Run @AfterAll
    if (afterAllMethod != null) {
      afterAllMethod.setAccessible(true);
      afterAllMethod.invoke(null); // Static method
    }
  }

  private static Method[] getAnnotatedMethods(Class<?> clazz, Class<? extends Annotation> annotation) {
    return Arrays.stream(clazz.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(annotation))
        .toArray(Method[]::new);
  }
}
