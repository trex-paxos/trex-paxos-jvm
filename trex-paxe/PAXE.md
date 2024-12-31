
# PAXE Protocol Specification v0.9

## Overview
PAXE is a lightweight encrypted protocol for Paxos clusters supporting multiplexed channels over UDP. It provides 0-RTT resumption for established sessions and 1-RTT authentication for new sessions.

## Packet Format
```
[UDP Header]
[PAXE Header - 4 bytes]
  From Node ID:    2 byte
  To Node ID:      3 byte  
  Channel ID:      2 byte  
  Flags:           1 byte
    bit 7: Auth Required     (0x80)
    bit 6: Is Fragmented    (0x40)
    bit 5: Fragment Start   (0x20)
    bit 4: Fragment End     (0x10)
    bits 3-0: Reserved
[Encryption Header - 28 bytes] 
  Nonce:           12 bytes 
  Auth Tag:        16 bytes
[Encrypted Payload]
  Type:            1 byte
  Length:          4 bytes
  Data:            variable
```

## Channels
- Channel 0: Reserved for PAXE consensus messages

## Fragmentation
Any message on any channel may be fragmented if it exceeds the MTU size:

1. Fragmentation is indicated by the Is Fragmented flag (0x40)
2. Fragment markers in flags:
   - First fragment: Fragment Start = 1
   - Middle fragments: Start = 0, End = 0  
   - Last fragment: Fragment End = 1

3. Fragment Header (in encrypted payload when Is Fragmented is set):
   ```
   Message ID:      16 bytes (UUID)
   Fragment Number: 4 bytes
   Fragment Size:   4 bytes
   Data:           variable
   ```

4. Fragment Acknowledgment Messages:
   ```
   Type:           1 byte (ACK)
   Message ID:     16 bytes
   Fragment Number: 4 bytes
   ```

## Session Keys
- Derived from SRP exchange between node pairs
- Key = HKDF(srp_shared_secret, "PAXE-V2", from_id | to_id)
- Same key used for both directions between node pair

## Message Flow
1. SRP authentication (1-RTT) if no session exists:
   - Indicated by Auth Required flag
   - Must complete before encrypted traffic
2. Encrypted stream communication (0-RTT) using channels
3. Automatic session resumption after network changes

## References
1. When large messages are fragmented:
   - Each fragment includes Message ID for reassembly
   - Receiving node must buffer and reorder fragments
   - Complete message processed after last fragment received
2. Applications must handle timeout and cleanup of incomplete fragment sequences
