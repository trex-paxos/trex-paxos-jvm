package com.github.trex_paxos.network;

import com.github.trex_paxos.TrexNode;
import java.util.Optional;

public interface RoleListener {
    void onRoleChange(NodeId nodeId, TrexNode.TrexRole oldRole, TrexNode.TrexRole newRole);
    void onLeaderChange(NodeId nodeId, Optional<NodeId> newLeader);
}