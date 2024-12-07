/*
 * Copyright 2024 Simon Massey
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
package com.github.trex_paxos.sherlock;

import com.github.trex_paxos.Command;

import java.util.concurrent.*;

public class CommandProcessor {
    private final BlockingQueue<String> workQueue;
    private final ConcurrentNavigableMap<String, CompletableFuture<Result>> pendingCommands;

    public CommandProcessor(BlockingQueue<String> workQueue, ConcurrentNavigableMap<String, CompletableFuture<Result>> pendingCommands) {
        this.workQueue = workQueue;
        this.pendingCommands = pendingCommands;
    }

    public CommandProcessor() {
        this.workQueue = new LinkedBlockingQueue<>();
        this.pendingCommands = new ConcurrentSkipListMap<>();
    }

    // Called by TCP reader virtual threads
    public CompletableFuture<Result> submitCommand(Command command) throws InterruptedException {
        CompletableFuture<Result> future = new CompletableFuture<>();
        pendingCommands.put(command.clientMsgUuid(), future);
        // Add the command to the work queue
        workQueue.put(command.clientMsgUuid());
        return future;
    }

    // Single virtual thread processing work
    public void processCommandsLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String commandId = workQueue.take(); // Blocks until work available
                Result result = doWork(commandId);
                CompletableFuture<Result> future = pendingCommands.remove(commandId);
                if (future != null) {
                    future.complete(result);
                }
            } catch (Exception e) {
                handleError(e);
            }
        }
    }

    private Result doWork(String commandId) {
            // Implementation of work processing
        return new Result(commandId);
    }

    private void handleError(Exception e) {
        // Implementation of error handling
        e.printStackTrace();
    }
}
