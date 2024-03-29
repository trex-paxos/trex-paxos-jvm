package com.github.trex_paxos;

public class PrepareHandler {
        static StateUpdate process(Progress progress, Prepare prepare, Journal journal, Messaging io) {
            var number = prepare.id().number();
            if (number.lessThan(progress.highestPromised())) {
                // nack a low prepare
                io.nack(prepare);
                return new StateUpdate(progress);
            } else if (number.greaterThan(progress.highestPromised())) {
                // ack a higher prepare and backdown if we are not a follower
                final var newProgress = progress.withHighestPromised(prepare.id().number());
                journal.saveProgress(newProgress);
                io.ack(prepare);
                return StateUpdate.backdown(newProgress);
            } else if (number.equals(progress.highestPromised())) {
                io.ack(prepare);
                return new StateUpdate(progress);
            } else {
                throw new IllegalArgumentException(String.format("%s:%s", prepare.getClass().getCanonicalName(), prepare));
            }
        }
}
