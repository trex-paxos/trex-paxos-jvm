// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/// This is a pickler for flat Record types. It only supports records that have components that are
/// primitive types, strings, and Optional<String>. This is good enough for all the messages in Trex.
public class FlatRecordPickler {
  public static <T extends Record> Pickler<T> createPickler(Class<T> recordClass) throws NoSuchMethodException, IllegalAccessException {
    if (!recordClass.isRecord()) {
      throw new IllegalArgumentException("Class must be a record");
    }
    RecordComponent[] components = recordClass.getRecordComponents();
    for (RecordComponent comp : components) {
      if (!isSupportedType(comp.getType())) {
        throw new IllegalArgumentException(
            "Unsupported type: " + comp.getType() + " for field: " + comp.getName()
        );
      }
    }
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    final MethodHandle[] componentAccessors = new MethodHandle[components.length];
    Arrays.setAll(componentAccessors, i -> {
      try {
        return lookup.unreflect(components[i].getAccessor());
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to access component check if the record type is public: " + components[i].getName(), e);
      }
    });

    // Extract component types for the constructor
    Class<?>[] paramTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);

    // Create method type for the canonical constructor
    MethodType constructorType = MethodType.methodType(void.class, paramTypes);
    MethodHandle constructorHandle = lookup.findConstructor(recordClass, constructorType);

    return new Pickler<>() {
      @Override
      public void serialize(T record, ByteBuffer buffer) {
        if (record == null) return;
        var index = 0;
        for (MethodHandle accessor : componentAccessors) {
              try {
                Object value = accessor.invokeWithArguments(record);
                Class<?> type = paramTypes[index++];
                writeToBuffer(buffer, type, value);
              } catch (Throwable e) {
                throw new RuntimeException("Error serializing field: " + accessor, e);
              }
            }
      }
      
      @Override
      public int sizeOf(T record) {
        if (record == null) return 0;
        
        int size = 0;
        int index = 0;
        for (MethodHandle accessor : componentAccessors) {
          try {
            Object value = accessor.invokeWithArguments(record);
            Class<?> type = paramTypes[index];
            size += FlatRecordPickler.sizeOf(type, value);
            index++;
          } catch (Throwable e) {
            throw new RuntimeException("Error accessing field: " + paramTypes[index].getName(), e);
          }
        }
        return size;
      }

      @Override
      public T deserialize(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() == 0) return null;

        // Mark the current position to reset if needed
        buffer.mark();

        try {
          Object[] args = new Object[componentAccessors.length];
          for (int i = 0; i < componentAccessors.length; i++) {
            args[i] = readFromBuffer(buffer, paramTypes[i]);
          }

          //noinspection unchecked
          return (T) constructorHandle.invokeWithArguments(args);
        } catch (Throwable e) {
          // Reset buffer to its original position
          buffer.reset();
          // Not enough data to deserialize the complete record
          return null;
        }
      }
    };
  }

  public static boolean isSupportedType(Class<?> type) {
    return type == int.class ||
        type == long.class ||
        type == boolean.class ||
        type == String.class ||
        type == Optional.class;
  }

  public static int sizeOf(Class<?> type, Object value) {
    if (type == int.class) return Integer.BYTES;
    if (type == long.class) return Long.BYTES;
    if (type == boolean.class) return 1;
    if (type == String.class) {
      if (value == null) return Integer.BYTES;
      byte[] bytes = ((String) value).getBytes();
      return Integer.BYTES + bytes.length;
    }
    if (type == Optional.class) {
      if (value == null) return 1; // Treat null Optional as empty
      Optional<?> opt = (Optional<?>) value;
      if (opt.isEmpty()) return 1;
      Object innerValue = opt.get();
      if (innerValue instanceof String) {
        byte[] bytes = ((String) innerValue).getBytes();
        return 1 + Integer.BYTES + bytes.length;
      }
      throw new IllegalArgumentException("Unsupported Optional type: " + innerValue.getClass());
    }
    throw new IllegalArgumentException("Unsupported type: " + type);
  }

  public static void writeToBuffer(ByteBuffer buffer, Class<?> type, Object value) {
    if (type == int.class) {
      buffer.putInt((Integer) value);
    } else if (type == long.class) {
      buffer.putLong((Long) value);
    } else if (type == boolean.class) {
      buffer.put((byte) ((Boolean) value ? 1 : 0));
    } else if (type == String.class) {
      if (value == null) {
        buffer.putInt(-1);
      } else {
        byte[] strBytes = ((String) value).getBytes();
        buffer.putInt(strBytes.length);
        if (strBytes.length > 0) {
          buffer.put(strBytes);
        }
      }
    } else if (type == Optional.class) {
      if (value == null) {
        buffer.put((byte) 0); // Treat null Optional as empty
      } else {
        Optional<?> opt = (Optional<?>) value;
        if (opt.isEmpty()) {
          buffer.put((byte) 0);
        } else {
          buffer.put((byte) 1);
          Object innerValue = opt.get();
          Class<?> innerType = innerValue.getClass();
          writeToBuffer(buffer, innerType, innerValue);
        }
      }
    }
  }

  public static Object readFromBuffer(ByteBuffer buffer, Class<?> type) {
    if (buffer.remaining() < getMinBytesForType(type)) {
      throw new java.nio.BufferUnderflowException();
    }

    if (type == int.class) {
      return buffer.getInt();
    } else if (type == long.class) {
      return buffer.getLong();
    } else if (type == boolean.class) {
      return buffer.get() == 1;
    } else if (type == String.class) {
      int length = buffer.getInt();
      if (length == -1) return null;
      if (length == 0) return "";

      // Check if we have enough bytes for the string
      if (buffer.remaining() < length) {
        throw new java.nio.BufferUnderflowException();
      }

      byte[] strBytes = new byte[length];
      buffer.get(strBytes);
      return new String(strBytes);
    } else if (type == Optional.class) {
      byte isPresent = buffer.get();
      if (isPresent == 0) return Optional.empty();

      // For Optional<String>, we need to check the inner type's requirements
      if (buffer.remaining() < Integer.BYTES) {
        throw new java.nio.BufferUnderflowException();
      }

      return Optional.ofNullable(readFromBuffer(buffer, String.class));
    }
    throw new IllegalArgumentException("Unsupported type: " + type);
  }

  // Helper method to determine minimum bytes needed for a type
  private static int getMinBytesForType(Class<?> type) {
    if (type == int.class) return Integer.BYTES;
    if (type == long.class) return Long.BYTES;
    if (type == boolean.class) return 1;
    if (type == String.class) return Integer.BYTES; // At least the length field
    if (type == Optional.class) return 1; // At least the presence byte
    throw new IllegalArgumentException("Unsupported type: " + type);
  }
}
