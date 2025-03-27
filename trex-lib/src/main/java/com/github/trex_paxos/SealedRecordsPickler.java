package com.github.trex_paxos;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/// This pickler serializes and deserializes sealed records. It creates pickler for each permitted subclass.
public class SealedRecordsPickler {
  public static <T> Pickler<T> createPickler(Class<T> sealedInterface) {
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

      // For empty records (no components), create a simple pickler
      if (recordClass.getRecordComponents() == null || recordClass.getRecordComponents().length == 0) {
        Constructor<?> constructor = recordClass.getDeclaredConstructors()[0];
        picklersByClass.put(subclass, new Pickler<Record>() {
          @Override
          public byte[] serialize(Record value) {
            return new byte[0];  // Empty record needs no data
          }

          @Override
          public Record deserialize(byte[] bytes) {
            try {
              return (Record) constructor.newInstance();
            } catch (Exception e) {
              throw new RuntimeException("Failed to create record instance", e);
            }
          }
        });
      } else {
        picklersByClass.put(subclass, FlatRecordPickler.createPickler(recordClass));
      }

      typeIdsByClass.put(subclass, nextTypeId);
      classesByTypeId.put(nextTypeId, subclass);
      nextTypeId++;
    }

    return new Pickler<>() {
      @Override
      public byte[] serialize(T value) {
        if (value == null) return new byte[0];

        Class<?> actualClass = value.getClass();
        Byte typeId = typeIdsByClass.get(actualClass);
        if (typeId == null) {
          throw new IllegalArgumentException("Unknown subclass: " + actualClass);
        }

        @SuppressWarnings("unchecked")
        Pickler<Object> pickler = (Pickler<Object>) picklersByClass.get(actualClass);
        byte[] recordBytes = pickler.serialize(value);

        ByteBuffer buffer = ByteBuffer.allocate(1 + recordBytes.length);
        buffer.put(typeId);
        buffer.put(recordBytes);
        return buffer.array();
      }

      @Override
      @SuppressWarnings("unchecked")
      public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte typeId = buffer.get();

        Class<?> recordClass = classesByTypeId.get(typeId);
        if (recordClass == null) {
          throw new IllegalArgumentException("Unknown type ID: " + typeId);
        }

        Pickler<?> pickler = picklersByClass.get(recordClass);
        byte[] recordBytes = new byte[bytes.length - 1];
        buffer.get(recordBytes);

        return (T) pickler.deserialize(recordBytes);
      }
    };
  }
}
