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
package com.github.trex_paxos;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.Optional;

/// This is a countVotes pickler for flat records. It only supports records that have components that are
/// primitive types, strings, and Optional<String>. This is good enough for all the messages in Trex.
/// Core java is working on making records serializable by default, but until then, this is good enough. 
public class FlatRecordPickler {

  public static <T extends Record> Pickler<T> createPickler(Class<T> recordClass) {
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

    @SuppressWarnings("unchecked")
    Constructor<T> constructor = (Constructor<T>) recordClass.getDeclaredConstructors()[0];

    return new Pickler<>() {
      @Override
      public byte[] serialize(T record) {
        if (record == null) return new byte[0];

        int size = 0;
        for (RecordComponent comp : components) {
          try {
            Object value = comp.getAccessor().invoke(record);
            size += sizeOf(comp.getType(), value);
          } catch (Exception e) {
            throw new RuntimeException("Error accessing field: " + comp.getName(), e);
          }
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (RecordComponent comp : components) {
          try {
            Object value = comp.getAccessor().invoke(record);
            writeToBuffer(buffer, comp.getType(), value);
          } catch (Exception e) {
            throw new RuntimeException("Error serializing field: " + comp.getName(), e);
          }
        }
        return buffer.array();
      }

      @Override
      public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Object[] args = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
          args[i] = readFromBuffer(buffer, components[i].getType());
        }

        try {
          return constructor.newInstance(args);
        } catch (Exception e) {
          throw new RuntimeException("Error creating record instance", e);
        }
      }
    };
  }

  private static boolean isSupportedType(Class<?> type) {
    return type == int.class ||
        type == long.class ||
        type == boolean.class ||
        type == String.class ||
        type == Optional.class;
  }

  private static int sizeOf(Class<?> type, Object value) {
    if (type == int.class) return Integer.BYTES;
    if (type == long.class) return Long.BYTES;
    if (type == boolean.class) return 1;
    if (type == String.class) {
      if (value == null) return Integer.BYTES;
      byte[] bytes = ((String) value).getBytes();
      return Integer.BYTES + bytes.length;
    }
    if (type == Optional.class) {
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

  private static void writeToBuffer(ByteBuffer buffer, Class<?> type, Object value) {
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
      Optional<?> opt = (Optional<?>) value;
      if (opt.isEmpty()) {
        buffer.put((byte) 0);
      } else {
        buffer.put((byte) 1);
        writeToBuffer(buffer, opt.get().getClass(), opt.get());
      }
    }
  }

  private static Object readFromBuffer(ByteBuffer buffer, Class<?> type) {
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
      byte[] strBytes = new byte[length];
      buffer.get(strBytes);
      return new String(strBytes);
    } else if (type == Optional.class) {
      byte isPresent = buffer.get();
      if (isPresent == 0) return Optional.empty();
      return Optional.ofNullable(readFromBuffer(buffer, String.class));
    }
    throw new IllegalArgumentException("Unsupported type: " + type);
  }
}
