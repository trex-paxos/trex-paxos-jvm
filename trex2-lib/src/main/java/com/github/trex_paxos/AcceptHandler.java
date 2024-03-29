package com.github.trex_paxos;

class AcceptHandler {
    /**
     * Handle an accept message.
     * @param progress   The current state of the agent.
     * @param accept The accept message to handle.
     * @param journal     The journal to record an equal or higher accept.
     * @Param io The messaging to send either an ack or nack.
     * @return The agents progress may have been updated with the highest promised number.
     */
    static StateUpdate process(Progress progress, Accept accept, Journal journal, Messaging io) {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept.id(), progress)) {
            io.nack(accept);
            return new StateUpdate(progress);
        } else if (equalOrHigherAccept(progress, accept)) {
            journal.journalAccept(accept);
            Progress updatedProgress;
            if (higherAccept(progress, accept)) {
                updatedProgress = progress.withHighestPromised(accept.id().number());
                journal.saveProgress(updatedProgress);
            } else {
                updatedProgress = progress;
            }
            io.ack(accept);
            return new StateUpdate(updatedProgress);
        } else {
            throw new IllegalArgumentException(STR."\{accept.getClass().getCanonicalName()}:\{accept.toString()}");
        }
    }

    private static boolean equalOrHigherAccept(Progress progress, Accept accept) {
        return progress.highestPromised().lessThanOrEqualTo(accept.id().number());
    }

    private static boolean higherAcceptForCommittedSlot(Identifier accept, Progress progress) {
        return accept.number().greaterThan(progress.highestPromised()) &&
                accept.logIndex() <= progress.highestCommitted().logIndex();
    }

    private static Boolean lowerAccept(Progress progress, Accept accept) {
        return accept.id().number().lessThan(progress.highestPromised());
    }

    private static Boolean higherAccept(Progress progress, Accept accept) {
        return accept.id().number().greaterThan(progress.highestPromised());
    }
}
