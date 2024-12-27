package com.github.trex_paxos;
import java.lang.reflect.*;
import java.nio.ByteBuffer;

public class RecordSerde {
    
    public static <T extends Record> SerDe<T> createSerde(Class<T> recordClass) {
        // Validate it's a record
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException("Class must be a record");
        }

        // Get record components in declaration order
        RecordComponent[] components = recordClass.getRecordComponents();
        
        // Validate all types are supported
        for (RecordComponent comp : components) {
            if (!isSupportedType(comp.getType())) {
                throw new IllegalArgumentException(
                    "Unsupported type: " + comp.getType() + " for field: " + comp.getName()
                );
            }
        }

        @SuppressWarnings("unchecked")
        Constructor<T> constructor = (Constructor<T>) recordClass.getDeclaredConstructors()[0];

        return new SerDe<T>() {
            @Override
            public byte[] serialize(T record) {
                if (record == null) {
                    return new byte[0];
                }

                // First pass to calculate buffer size
                int size = 0;
                for (RecordComponent comp : components) {
                    try {
                        Object value = comp.getAccessor().invoke(record);
                        size += sizeOf(comp.getType(), value);
                    } catch (Exception e) {
                        throw new RuntimeException("Error accessing field: " + comp.getName(), e);
                    }
                }

                // Allocate buffer and write fields
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
                if (bytes == null || bytes.length == 0) {
                    return null;
                }

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
               type == String.class;
    }

    private static int sizeOf(Class<?> type, Object value) {
        if (type == int.class) return Integer.BYTES;
        if (type == long.class) return Long.BYTES;
        if (type == boolean.class) return 1;
        if (type == String.class) {
            if (value == null) return Integer.BYTES;
            byte[] bytes = ((String)value).getBytes();
            return Integer.BYTES + bytes.length;
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private static void writeToBuffer(ByteBuffer buffer, Class<?> type, Object value) {
        if (type == int.class) {
            buffer.putInt((Integer)value);
        } else if (type == long.class) {
            buffer.putLong((Long)value);
        } else if (type == boolean.class) {
            buffer.put((byte)((Boolean)value ? 1 : 0));
        } else if (type == String.class) {
            if (value == null) {
                buffer.putInt(-1);
            } else {
                byte[] strBytes = ((String)value).getBytes();
                buffer.putInt(strBytes.length);
                if (strBytes.length > 0) {
                    buffer.put(strBytes);
                }
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
        if (length == -1) {
            return null;
        }
        if (length == 0) {
            return "";
        }
        byte[] strBytes = new byte[length];
        buffer.get(strBytes);
        return new String(strBytes);
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }
}
