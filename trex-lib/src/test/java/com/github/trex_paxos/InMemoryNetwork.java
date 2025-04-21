// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ChannelSubscription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.github.trex_paxos.TrexLogger.LOGGER;

record ChannelAndSubscriber(Channel channel, ChannelSubscription subscriber) {
}
class InMemoryNetwork {
  private final List<ChannelAndSubscriber> handlers = new ArrayList<>();
  private final LinkedBlockingQueue<NetworkMessage> messageQueue = new LinkedBlockingQueue<>();
  private volatile boolean running = true;

  public InMemoryNetwork() {
    LOGGER.fine(() -> "Created InMemoryNetwork");
  }

  private record NetworkMessage(short nodeId, Channel channel, ByteBuffer data) {
  }

  public void send(Channel channel, short nodeId, ByteBuffer data) {
    if (running) {
      messageQueue.add(new NetworkMessage(nodeId, channel, data));
    }
  }

  public void subscribe(Channel channel, ChannelSubscription handler) {
    ChannelAndSubscriber channelAndSubscriber = new ChannelAndSubscriber(channel, handler);
    handlers.add(channelAndSubscriber);
  }

  public void start() {
    Thread.ofVirtual().name("network").start(() -> {
      while (running) {
        try {
          NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);

          if (msg != null) {
            msg.data().flip();
            msg.data().mark();
            handlers.forEach(h -> {
              if (h.channel().equals(msg.channel)) {
                LOGGER.fine(() -> InMemoryNetwork.class.getSimpleName() + " received message on channel " + msg.channel + " from " + msg.nodeId + " delivering to " + h.subscriber().name());
                h.subscriber().accept(msg.data);
                msg.data().reset();
              }
            });
          }
        } catch (InterruptedException e) {
          if (running) {
            LOGGER.warning(InMemoryNetwork.class.getSimpleName() + " message processor interrupted: " + e.getMessage());
          }
        }
      }
    });
    LOGGER.info(() -> InMemoryNetwork.class.getSimpleName() + " network started with subscribers " + handlers);
  }

  public void close() {
    LOGGER.fine(() -> InMemoryNetwork.class.getSimpleName() + " network stopping");
    running = false;
  }
}
