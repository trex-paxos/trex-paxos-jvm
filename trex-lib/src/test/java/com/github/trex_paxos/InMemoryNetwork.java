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

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ChannelSubscription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class InMemoryNetwork {
  private final List<StackServiceImpl.ChannelAndSubscriber> handlers = new ArrayList<>();
  private final LinkedBlockingQueue<NetworkMessage> messageQueue = new LinkedBlockingQueue<>();
  private volatile boolean running = true;

  public InMemoryNetwork() {
    StackServiceImpl.LOGGER.fine(() -> "Created InMemoryNetwork");
  }

  private record NetworkMessage(short nodeId, Channel channel, ByteBuffer data) {
  }

  public void send(Channel channel, short nodeId, ByteBuffer data) {
    if (running) {
      messageQueue.add(new NetworkMessage(nodeId, channel, data));
    }
  }

  public void subscribe(Channel channel, ChannelSubscription handler) {
    StackServiceImpl.ChannelAndSubscriber channelAndSubscriber = new StackServiceImpl.ChannelAndSubscriber(channel, handler);
    handlers.add(channelAndSubscriber);
  }

  public void start() {
    Thread.ofVirtual().name("network").start(() -> {
      while (running) {
        try {
          NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);

          if (msg != null) {
            handlers.forEach(h -> {
              if (h.channel().equals(msg.channel)) {
                StackServiceImpl.LOGGER.fine(() -> InMemoryNetwork.class.getSimpleName() + " received message on channel " + msg.channel + " from " + msg.nodeId + " delivering to " + h.subscriber().name());
                h.subscriber().accept(msg.data);
              }
            });
          }
        } catch (InterruptedException e) {
          if (running) {
            StackServiceImpl.LOGGER.warning(InMemoryNetwork.class.getSimpleName() + " message processor interrupted: " + e.getMessage());
          }
        }
      }
    });
    StackServiceImpl.LOGGER.info(() -> InMemoryNetwork.class.getSimpleName() + " network started with subscribers " + handlers);
  }

  public void close() {
    StackServiceImpl.LOGGER.fine(() -> InMemoryNetwork.class.getSimpleName() + " network stopping");
    running = false;
  }
}
