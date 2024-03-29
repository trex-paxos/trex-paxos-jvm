package com.github.trex_paxos;

/**
 * A state update is a message from the state machine to the paxos instance of new progress and whether to backdown.
 * The backdown flag is used to indicate that the paxos instance should backdown to the leader.
 * @param progress The updated progress.
 * @param backdown Whether to backdown to the leader.
 */
public record StateUpdate(Progress progress, boolean backdown) {
    public StateUpdate {
        if (progress == null) {
            throw new IllegalArgumentException("progress cannot be null");
        }
    }


    public static StateUpdate backdown(Progress progress) {
        return new StateUpdate(progress, false);
    }

    public StateUpdate(Progress progress) {
        this(progress, false);
    }
}
