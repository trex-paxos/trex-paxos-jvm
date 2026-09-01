# Paxe Protocol Documentation

## Overview

Paxe implements authenticated encryption for Trex Paxos messages using AES-256-GCM. Every cluster
member holds the same 32-byte pre-shared key (PSK) per epoch, installed out of band. PAXE is a
protected intracluster datagram codec, not a key-agreement protocol.

## Wire Format

### Prefix (9 bytes)

```
+--------+--------+-------------+--------+
| fromId | toId   | channel u32 | epoch  |
| 2 BE   | 2 BE   | 4 BE        | 1 byte |
+--------+--------+-------------+--------+
```

- `fromId` / `toId`: unsigned big-endian node identifiers (authenticated routing metadata)
- `channel`: unsigned big-endian `u32` application multiplexing identifier
- `epoch`: cluster-key epoch for coordinated rotation

### Frame layout

```
+----------------+-----------+------------+-----------------+
| Prefix (9)     | Nonce(12) | Ciphertext | Auth Tag (16)  |
+----------------+-----------+------------+-----------------+
```

Fixed overhead is 37 bytes. Plaintext length is `datagram_length - 37`; there is no encoded length
field.

AES-GCM associated data is the exact nine prefix bytes carried in the received frame.

## Key provisioning

### Data plane

Install a cryptographically random 32-byte cluster PSK on every member. For coordinated rotation,
install the next epoch key, accept overlap, switch senders to the new epoch, then retire the old
epoch.

### Control plane (TLS 1.3)

Optional TLS 1.3 external-PSK provisioning distributes or rotates the cluster PSK to members:

- **Bootstrap PSK** — authenticates the TLS provisioning channel only; must not be installed as the
  PAXE cluster data key.
- **Cluster PSK** — the shared 32-byte AES-256 key installed into `ClusterKeyManager` for a given
  epoch after a successful provision.
- **Epoch** — coordinates rotation; the provisioner delivers the cluster PSK for the requested
  epoch.

The provisioner uses TLS 1.3 `psk_dhe_ke` with ephemeral X25519, rejects PSK-only `psk_ke`, and
disables 0-RTT application data. TLS AEAD cipher suites (for example `TLS_AES_128_GCM_SHA256`) are
independent of the PSK key-exchange mode.

`ClusterPskProvisionerServer` and `ClusterPskProvisionerClient` implement the control plane. Demo
operators can use `ClusterStackAdmin serve-provision` and `provision` instead of manual hex
copy/paste; `set-psk` remains available for local development.

## Key classes

### `PaxeNetwork`

UDP send/receive, channel multiplexing, and AES-GCM sealing with the cluster PSK.

### `ClusterKeyManager`

Holds cluster PSK material indexed by epoch.

### `PaxePacket`

Canonical prefix serialization, seal/open helpers, and tamper detection.

## Security properties

- One cluster PSK per epoch opens frames from every configured member; no pairwise key lookup exists.
- Tampering with any prefix, nonce, ciphertext, or tag byte prevents acceptance.
- Truncated or extended datagrams are rejected before releasing plaintext.
- Broadcast to multiple nodes uses independent sealed datagrams; there is no DEK/KEK mode.

## Usage

- Maximum plaintext size: `65507 - 37 = 65470` bytes per datagram.
- All `u32` channel values belong to the host application; there is no reserved SRP/system range.

## Test support

- `NetworkTestHarness` builds multi-node UDP test clusters with a shared cluster PSK.
- `InMemoryNetwork` in `trex-lib` remains available for algorithm tests without PAXE.
