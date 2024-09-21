package com.github.trex_paxos;

import com.github.trex_paxos.msg.Command;
import com.github.trex_paxos.msg.Commit;
import com.github.trex_paxos.msg.Prepare;

public interface HostApplication {
  /**
   * A callback for when a value is committed. This functional interface is called with the chosen command in the log
   * committed order. It is anticipated that the application will use this to apply the command to the application state.
   * It will then use the `clientMsgUuid` of the command to reply to the waiting client. As the callback is invoked on
   * every node eventually the operation will be applied to the application state on every node. The client will only be
   * awaiting a reply from one node in the cluster. So only one node will use the `clientMsgUuid` reply to the client.
   */
  void upCall(Command chosenCommand);

  /**
   * A callback to send out a heartbeat commit to the rest of the cluster. This is used to prevent a leadership battle.
   * This is only sent out by a leader node.
   *
   * @param commit The latest commit of this leader
   */
  void heartbeat(Commit commit);

  /**
   * A callback when a node has timed out on waiting for a heartbeat commit from the leader.
   *
   * @param prepare The candidate prepare message to send to the rest of the cluster.
   */
  void timeout(Prepare prepare);
}
