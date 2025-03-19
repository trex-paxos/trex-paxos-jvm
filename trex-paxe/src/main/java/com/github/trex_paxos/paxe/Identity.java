package com.github.trex_paxos.paxe;

public record Identity(short nodeId, String cluster) {
  public String full() {
    return nodeId + "@" + cluster;
  }

  public static Identity from(String id) {
    var parts = id.split("@", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("Identity must be in format nodeId@cluster");
    }
    try {
      return new Identity(Short.parseShort(parts[0]), parts[1]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid nodeId as cannot parse as a short: " + parts[0]);
    }
  }

}
