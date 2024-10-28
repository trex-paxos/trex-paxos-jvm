package com.github.trex_paxos.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for simple App.
 */
public class AppTest {

    private CommandServer commandServer;
    private Thread serverThread;

    @BeforeEach
    public void setUp() throws IOException {
        int port = 0; // Bind to a random port
        commandServer = new CommandServer(port, new CommandProcessor());
        serverThread = new Thread(() -> {
            commandServer.start();
        });
        serverThread.start();

        // Wait for the server to start and bind to the port
        while (commandServer.getPort() == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @AfterEach
    public void tearDown() throws IOException {
        commandServer.close();
        serverThread.interrupt();
    }

    @Test
    public void testApp() throws IOException {
        String clientMsgUuid = "123e4567-e89b-12d3-a456-426614174000";
        String message = "Hello, Server!";
        AtomicReference<String> receivedResponse = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);

        Socket socket = new Socket("localhost", commandServer.getPort());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        final DataInputStream in = new DataInputStream(socket.getInputStream());

        // Create a separate thread for reading server responses
        Thread readThread = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    in.mark(1);
                    if (in.read() == -1) {
                        break;
                    }
                    in.reset();

                    int responseLength = in.readInt();
                    byte[] responseData = new byte[responseLength];
                    in.readFully(responseData);
                    String response = new String(responseData, "UTF-8");
                    receivedResponse.set(response);
                    responseLatch.countDown();
                    break;
                }
            } catch (IOException e) {
                // Handle exception appropriately
            }
        });
        readThread.start();

        // Send the message
        byte[] uuidData = clientMsgUuid.getBytes("UTF-8");
        out.writeInt(uuidData.length);
        out.write(uuidData);
        out.flush();

        byte[] messageData = message.getBytes("UTF-8");
        out.writeInt(messageData.length);
        out.write(messageData);
        out.flush();

        // Wait for response with timeout
        try {
            boolean responseReceived = responseLatch.await(5, TimeUnit.SECONDS);
            if (!responseReceived) {
                throw new AssertionError("Response timeout");
            }
            // Verify the response
            String expectedResponse = "Received: " + message;
            assertEquals(expectedResponse, receivedResponse.get());

            socket.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test interrupted");
        } finally {
            socket.close();
        }

    }
}