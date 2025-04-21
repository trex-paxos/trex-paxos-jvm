// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/// This pickler serializes and deserializes sealed records. It creates pickler for each permitted subclass.
public class SealedRecordsPickler {

  public static <T> Pickler<T> createPickler(Class<T> sealedInterface) throws Exception {
    if (!sealedInterface.isSealed()) {
      throw new IllegalArgumentException("Class must be a sealed interface");
    }

    // Get all permitted subclasses
    Class<?>[] permittedSubclasses = sealedInterface.getPermittedSubclasses();
    if (permittedSubclasses == null || permittedSubclasses.length == 0) {
      throw new IllegalArgumentException("Sealed interface must have permitted subclasses");
    }

    // Verify all subclasses are records and create serializers for each
    Map<Class<?>, Pickler<?>> picklersByClass = new HashMap<>();
    Map<Class<?>, Byte> typeIdsByClass = new HashMap<>();
    Map<Byte, Class<?>> classesByTypeId = new HashMap<>();

    byte nextTypeId = 0;
    for (Class<?> subclass : permittedSubclasses) {
      if (!subclass.isRecord()) {
        throw new IllegalArgumentException("All permitted subclasses must be records: " + subclass);
      }

      @SuppressWarnings("unchecked")
      Class<? extends Record> recordClass = (Class<? extends Record>) subclass;

      // For empty records (no components), create a special empty record pickler
      if (recordClass.getRecordComponents() == null || recordClass.getRecordComponents().length == 0) {
        try {
          Constructor<?> constructor = recordClass.getDeclaredConstructors()[0];
          constructor.setAccessible(true);
          Record emptyInstance = (Record) constructor.newInstance();

          Pickler<?> pickler = createEmptyRecordPickler(emptyInstance);
          picklersByClass.put(subclass, pickler);
        } catch (Exception e) {
          throw new RuntimeException("Failed to create empty record pickler for: " + recordClass, e);
        }
      } else {
        picklersByClass.put(subclass, FlatRecordPickler.createPickler(recordClass));
      }

      typeIdsByClass.put(subclass, nextTypeId);
      classesByTypeId.put(nextTypeId, subclass);
      nextTypeId++;
    }

    return new Pickler<>() {
      @Override
      public void serialize(T value, ByteBuffer buffer) {
        if (value == null) return;

        Class<?> actualClass = value.getClass();
        Byte typeId = typeIdsByClass.get(actualClass);
        if (typeId == null) {
          throw new IllegalArgumentException("Unknown subclass: " + actualClass);
        }

        @SuppressWarnings("unchecked")
        Pickler<Object> pickler = (Pickler<Object>) picklersByClass.get(actualClass);
        int recordSize = pickler.sizeOf(value);
        
        buffer.put(typeId);
        ByteBuffer recordBuffer = ByteBuffer.allocate(recordSize);
        pickler.serialize(value, recordBuffer);
        recordBuffer.flip();
        buffer.put(recordBuffer);
      }
      
      @Override
      public int sizeOf(T value) {
        if (value == null) return 0;
        
        Class<?> actualClass = value.getClass();
        Byte typeId = typeIdsByClass.get(actualClass);
        if (typeId == null) {
          throw new IllegalArgumentException("Unknown subclass: " + actualClass);
        }
        
        @SuppressWarnings("unchecked")
        Pickler<Object> pickler = (Pickler<Object>) picklersByClass.get(actualClass);
        // 1 byte for the type ID + the size of the serialized record
        return 1 + pickler.sizeOf(value);
      }

      @Override
      @SuppressWarnings("unchecked")
      public T deserialize(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 1) return null;

        // Mark the current position to reset if needed
        buffer.mark();

        try {
          byte typeId = buffer.get();
          Class<?> recordClass = classesByTypeId.get(typeId);
          if (recordClass == null) {
            throw new IllegalArgumentException("Unknown type ID: " + typeId);
          }

          Pickler<?> pickler = picklersByClass.get(recordClass);
          return (T) pickler.deserialize(buffer);
        } catch (java.nio.BufferUnderflowException e) {
          // Reset buffer to its original position
          buffer.reset();
          // Not enough data to deserialize the complete record
          return null;
        }
      }
    };
  }

  /**
   * Creates a pickler for empty records (records with no components)
   * @param instance The pre-created instance of the empty record
   * @return A pickler that handles the empty record
   */
  static <T extends Record> Pickler<T> createEmptyRecordPickler(T instance) {

    return new Pickler<>() {
      @Override
      public void serialize(T object, ByteBuffer buffer) {
        // Empty record has no fields to serialize
      }

      @Override
      public T deserialize(ByteBuffer buffer) {
        // Return the pre-created instance since all instances are identical
        return instance;
      }

      @Override
      public int sizeOf(T value) {
        // Empty record takes no bytes
        return 0;
      }
    };
  }
}
