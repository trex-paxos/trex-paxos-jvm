
volatile long consumerSequence = 0;

// UDP channel configuration
final int port = 9876;

/// This method implements a high-performance, zero-copy data pipeline using the Disruptor pattern.
/// It sets up a ring buffer of pre-allocated DirectByteBuffers to transfer data from a UDP socket
/// to a memory-mapped file without intermediate copies. The implementation uses two threads:
/// a producer that reads from the UDP socket and a consumer that writes to the file.
///
/// The Disruptor pattern is employed for efficient inter-thread communication, using sequence
/// counters for coordination and leveraging `lazySet` for optimized memory visibility.
///
/// ## Sequence Diagram
///
/// ```
/// sequenceDiagram
///     participant UDP Socket
///     participant Producer Thread
///     participant Ring Buffer
///     participant Consumer Thread
///     participant Memory Mapped File
///
///     UDP Socket->>Producer Thread: Receive data
///     Producer Thread->>Ring Buffer: Write to next slot
///     Producer Thread->>Ring Buffer: Update sequence (lazySet)
///     Consumer Thread->>Ring Buffer: Check for new data
///     Consumer Thread->>Ring Buffer: Read from slot
///     Consumer Thread->>Memory Mapped File: Write data
///     Consumer Thread->>Ring Buffer: Update consumer sequence
/// ```
///
/// ## Key Components
///
/// - Ring Buffer: An array of pre-allocated DirectByteBuffers
/// - Producer Sequence: Tracks the last written position in the buffer
/// - Consumer Sequence: Tracks the last read position in the buffer
/// - DatagramChannel: For receiving UDP packets
/// - FileChannel: For writing to a memory-mapped file
///
/// ## Performance Considerations
///
/// - Zero-copy operations minimize data transfer overhead
/// - `lazySet` is used for efficient sequence updates
/// - `Thread.onSpinWait()` is used for efficient busy-waiting
/// - Pre-allocated DirectByteBuffers reduce GC pressure
///
/// @throws Exception If an I/O error occurs during setup or execution
@SuppressWarnings("preview")
void main() throws Exception {
  // Disruptor configuration
  final int BUFFER_SIZE = 1024;  // Size of each DirectByteBuffer
  final int RING_SIZE = 16;      // Number of buffers in the ring

  // Pre-allocate DirectByteBuffer array for the ring buffer using IntStream
  ByteBuffer[] ringBuffer = IntStream.range(0, RING_SIZE)
      .mapToObj(_ -> ByteBuffer.allocateDirect(BUFFER_SIZE))
      .toArray(ByteBuffer[]::new);

  // Sequence counters
  AtomicLong producerSequence = new AtomicLong(0);

  DatagramChannel datagramChannel = DatagramChannel.open()
      .setOption(StandardSocketOptions.SO_RCVBUF, BUFFER_SIZE)
      .bind(new InetSocketAddress(port));

  // Output file configuration
  Path filePath = Path.of("output.dat");
  Files.deleteIfExists(filePath); // Start with a fresh file
  FileChannel fileChannel = FileChannel.open(filePath,
      EnumSet.of(
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.READ
      ));

  // Position tracker for the file
  final AtomicLong filePosition = new AtomicLong(0);

  // Producer thread - reads from UDP socket into ring buffer with zero-copy
  Thread producer = Thread.ofPlatform().name("udp-reader").start(() -> {
    try {
      println(Thread.currentThread().getName()+ " started");
      while (!Thread.currentThread().isInterrupted()) {
        // Claim next slot
        long sequence = producerSequence.get();

        // Wait if buffer is full
        while (sequence - consumerSequence >= RING_SIZE) {
          Thread.onSpinWait();
        }

        // Get the buffer at the current sequence using simple modulo
        int index = (int) (sequence % RING_SIZE);
        ByteBuffer buffer = ringBuffer[index];
        buffer.clear();

        // Zero-copy receive directly into the DirectByteBuffer
        InetSocketAddress sender = (InetSocketAddress) datagramChannel.receive(buffer);
        if (sender != null) {
          buffer.flip();

          // Publish the sequence
          producerSequence.lazySet(sequence + 1);

          println(Thread.currentThread().getName() + " " + sequence + 1);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  });

  // Consumer thread - writes from ring buffer to file with zero-copy
  Thread consumer = Thread.ofPlatform().name("file-writer").start(() -> {
    try {
      long nextSequence = consumerSequence;
      println(Thread.currentThread().getName()+ " started");
      while (!Thread.currentThread().isInterrupted()) {
        long availableSequence = producerSequence.get();

        // Process available entries
        while (nextSequence < availableSequence) {
          // Use simple modulo for index calculation
          int index = (int) (nextSequence % RING_SIZE);
          ByteBuffer buffer = ringBuffer[index];

          // Zero-copy write from DirectByteBuffer to file
          long bytesWritten = fileChannel.write(buffer, filePosition.get());
          var bytes= filePosition.addAndGet(bytesWritten);

          println(Thread.currentThread().getName()+ bytes);

          // Reset buffer for reuse
          buffer.clear();

          // Move to next sequence
          nextSequence++;
        }

        // Update consumer sequence
        consumerSequence = nextSequence;

        // If no more events, use onSpinWait
        if (nextSequence == availableSequence) {
          Thread.onSpinWait();
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  });

  // Let it run for a while
  System.out.println("Disruptor running. Press Enter to stop...");
  //noinspection ResultOfMethodCallIgnored
  System.in.read();

  // Cleanup
  producer.interrupt();
  consumer.interrupt();
  datagramChannel.close();
  fileChannel.close();

  System.out.println("Bytes written to file: " + filePosition.get());
}
