// UDP channel configuration
int port = 9876;
@SuppressWarnings("preview")
void main() {
  try {
    var socket = new java.net.DatagramSocket();
    var address = java.net.InetAddress.getLocalHost();

    long counter = 0;
    long totalBytesSent = 0;
    long targetBytes = 1_000_000; // 1M of data

    while (totalBytesSent < targetBytes) {
      var uuid = java.util.UUID.randomUUID();
      var message = counter++ + ":" + uuid + "\n";
      var messageBytes = message.getBytes();

      var packet = new java.net.DatagramPacket(
          messageBytes,
          messageBytes.length,
          address,
          port
      );

      socket.send(packet);
      totalBytesSent += messageBytes.length;

      println("Sent packet: " + message.trim() + " | Total bytes: " + totalBytesSent);

      // Optional small delay to prevent flooding
      java.lang.Thread.sleep(5);
    }

    println("Completed sending " + counter + " packets");
    println("Total bytes sent: " + totalBytesSent);
    socket.close();
  } catch (Exception e) {
    println("Error: " + e.getMessage());
  }
}
