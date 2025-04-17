// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.advisory_locks.server;

import java.util.concurrent.*;

import com.github.trex_paxos.Command;

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
