package com.github.trex_paxos;

/**
 * A callback for when a value is committed. This functional interface is called with the chosen command in the log
 * committed order. It is anticipated that the application will use this to apply the command to the application state.
 * It will then use the `clientMsgUuid` of the command to reply to the waiting client. As the callback is invoked on
 * every node eventually the operation will be applied to the application state on every node. The client will only be
 * awaiting a reply from one node in the cluster. So only one node will use the `clientMsgUuid` reply to the client.
 */
@FunctionalInterface
public interface UpCall {
  void committed(Command chosenCommand);
}
