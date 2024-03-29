package com.github.trex_paxos;

<<<<<<< HEAD
public interface AcceptHandler {

    default PaxosData handleAccept(PaxosData data, Accept accept, Journal journal, Messaging io) {
        if ( lowerAccept(data, accept) || higherAcceptForCommittedSlot(accept.id(), data)) {
            io.nack(accept);
            return data;
        } else if (equalOrHigherAccept(data, accept)){
            return handleEqualOrHigherAccept(data, accept, journal, io);
=======
import java.util.function.Function;

public interface AcceptHandler {

    default PaxosData handleAccept(PaxosIO io, PaxosAgent agent, Accept accept) {
        Function<Identifier, Boolean> lowerAccept =
                id -> id.number().
                        lessThan(
                                agent.data().progress().highestPromised());
        Function<Identifier, Boolean> higherAcceptForCommittedSlot =
                id -> id.number().greaterThan(
                            agent.data().progress().highestPromised())
                        &&
                        id.logIndex() <= agent.data().progress().highestCommitted().logIndex();

        if ((lowerAccept.apply(accept.id())) || (higherAcceptForCommittedSlot.apply(accept.id()))) {
            io.nack(accept);
            return agent.data();
        } else if (agent.data().progress().highestPromised(). lessThanOrEqualTo ( accept.id().number()) ){
            return handleHighAccept(io, agent.data(), accept);
>>>>>>> lost-in-jj
        } else {
            throw new IllegalArgumentException(STR."\{accept.getClass().getCanonicalName()}:\{accept.toString()}");
        }
    }

<<<<<<< HEAD
    private static boolean equalOrHigherAccept(PaxosData data, Accept accept) {
        return data.progress().highestPromised().lessThanOrEqualTo(accept.id().number());
    }

    private static boolean higherAcceptForCommittedSlot(Identifier accept, PaxosData data) {
        return accept.number().greaterThan(data.progress().highestPromised()) &&
                accept.logIndex() <= data.progress().highestCommitted().logIndex();
    }

    private static Boolean lowerAccept(PaxosData data, Accept accept) {
        return accept.id().number().lessThan(data.progress().highestPromised());
    }

    default PaxosData handleEqualOrHigherAccept(PaxosData data, Accept accept, Journal io, Messaging messaging) {
        if (data.progress().highestPromised().greaterThan(accept.id().number())) {
            throw new IllegalArgumentException("Accept number must be greater or equal to the agent's promise.");
        }
        io.journalAccept(accept);
        PaxosData updatedData;
        if (higherAccept(data, accept)) {
            updatedData = data.withProgress(data.progress().withHighestPromised(accept.id().number()));
            io.journalProgress(updatedData.progress());
        } else {
            updatedData = data;
        }
        messaging.ack(accept);
        return updatedData;
    }

    private static Boolean higherAccept(PaxosData data, Accept accept) {
        return accept.id().number().greaterThan(data.progress().highestPromised());
    }
=======
    default PaxosData handleHighAccept(PaxosIO io, PaxosData data, Accept accept) {
        if (data.progress().highestPromised().greaterThan(accept.id().number())) {
            throw new IllegalArgumentException("Accept number must be greater or equal to the agent's promise.");
        }
        io.journal().accept(accept);
        PaxosData updatedData;
        if (accept.id().number().
                greaterThan(
                        data.progress().highestPromised())) {
            updatedData = highestPromisedSet(data, accept.id().number());
            io.journal().accept(updatedData.progress());
        } else {
            updatedData = data;
        }
        io.ack(accept);
        return updatedData;
    }

    default PaxosData highestPromisedSet(PaxosData data, BallotNumber number){
        throw new AssertionError("Not implemented");
    };
>>>>>>> lost-in-jj
}
