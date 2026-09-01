# Open-issue triage after #26 / PR #27

**Date:** 2026-09-01  
**Context:** Issue [#26](https://github.com/trex-paxos/trex-paxos-jvm/issues/26) (*Adopt cluster-PSK-only PAXE framing and retire SRP and reusable-DEK designs*) was implemented and merged as PR [#27](https://github.com/trex-paxos/trex-paxos-jvm/pull/27).

This report reviews the five remaining open issues and recommends closures, follow-ups, and a single superseding issue for TLS-based cluster-PSK provisioning.

---

## What PR #27 delivered

PR #27 is the authoritative end state for PAXE security in this repository:

| Area | Before (#23–#25 design track) | After (#26 / PR #27) |
|------|------------------------------|----------------------|
| Key model | Pairwise PSK per `(fromId, toId, epoch)` via provider | **One cluster PSK per epoch** via `ClusterKeyManager` |
| Wire prefix | 8-byte header + flags byte (epoch in flags) | **9-byte prefix:** `BE16(fromId) \|\| BE16(toId) \|\| BE32(channel) \|\| epoch` |
| Plaintext length | Encoded in header | **Derived** from UDP datagram size (`length - 37`) |
| Broadcast | Reusable-DEK / KEK fan-out mode | **Independent sealed datagrams** per recipient |
| Key establishment | In-band SRP6a on channel 0 | **Out-of-band only**; no handshake on the wire |
| SRP6a / RFC 5054 | `SRPUtils`, `SessionKeyManager`, verifiers | **Deleted** (production code, tests, demo paths) |
| TLS provisioning | Pairwise exporter-derived PSK (#25) | **Not implemented**; README mentions optional future TLS transport of the **cluster** PSK |

Remaining SRP references are documentation-only (e.g. README note that there is no reserved SRP channel range; stale `SessionKeyManager` comments in unrelated `trex-lib` tests). No RFC 5054 code remains.

---

## Per-issue verdict

| Issue | Title | Verdict | Action |
|-------|-------|---------|--------|
| [#20](https://github.com/trex-paxos/trex-paxos-jvm/issues/20) | incorrect DEK key size in `Crypto.java` JavaDoc | **Close — obsolete** | Duplicate of #26 |
| [#22](https://github.com/trex-paxos/trex-paxos-jvm/issues/22) | Document commit-index piggybacking / idle commits | **Leave open — unrelated** | No action from PR #27 |
| [#23](https://github.com/trex-paxos/trex-paxos-jvm/issues/23) | Decouple PAXE from SRP6a; inject out-of-band PSKs | **Close — done (design narrowed)** | Duplicate of #26 |
| [#24](https://github.com/trex-paxos/trex-paxos-jvm/issues/24) | Authenticated PAXE wire format + reusable-DEK broadcast | **Close — superseded** | Duplicate of #26 |
| [#25](https://github.com/trex-paxos/trex-paxos-jvm/issues/25) | Replace SRP6a with TLS 1.3 external PSK + ECDHE | **Close — partially done; design changed** | Duplicate of #26 for SRP removal; TLS work moves to new issue |

---

## Issue-by-issue detail

### #20 — `(docs) incorrect DEK key size in Crypto.java JavaDoc comment`

**Status: close as not needed (duplicate of #26).**

- `Crypto.java` was **deleted** in PR #27.
- The reusable-DEK / KEK broadcast mode the comment described was **removed entirely**.
- Issue #24 already noted that fixing this one sentence would not resolve the larger format contradictions.

**Implication of #26:** The underlying code and documentation artifact no longer exist. There is nothing left to fix.

---

### #22 — `Document commit-index piggybacking and idle commit announcements if present`

**Status: leave open — unrelated to PAXE / #26.**

- Scope is **trex-lib Paxos algorithm documentation** (Viewstamped Replication Revisited §4.1 step 6), not PAXE wire format or key management.
- PR #27 touched only `trex-paxe`, `Channel`/`SystemChannel`, CI, and build config. No changes to leader commit-announcement behaviour in `TrexNode`.
- Trex uses `Fixed` messages for learning committed slots; whether that matches VR's piggybacked commit index or idle `COMMIT` messages still needs explicit investigation and documentation.

**Implication of #26:** None. This issue stands on its own.

---

### #23 — `Decouple PAXE from SRP6a and inject out-of-band pre-shared keys`

**Status: close as duplicate of #26.**

PR #27 satisfies the core acceptance criteria:

- ✅ No in-band SRP, channel-0 key exchange, pending-until-handshake queues, or automatic re-establishment
- ✅ PSK installed out of band via `ClusterKeyManager`
- ✅ Epoch overlap and retirement supported
- ✅ Missing receive key → uniform drop; missing send key → configuration failure
- ✅ Tests pass with injected cluster PSK and no SRP objects
- ✅ Documentation rewritten (`trex-paxe/README.md`, `package-info.java`)

**Design divergence (intentional, per #26):**

- #23 described a **pairwise** `psk(localId, toId, epoch)` provider abstraction and allowed retaining SRP as an optional external adapter.
- #26 / PR #27 chose a **cluster-wide** key only (`ClusterKeyManager`) and **deleted** SRP rather than moving it behind an adapter.

The decoupling goal is complete; the pairwise-provider and SRP-adapter paths were explicitly rejected.

---

### #24 — `Define and implement the authenticated PAXE wire format with reusable-DEK broadcast`

**Status: close as duplicate of #26.**

This issue specified a format that #26 explicitly retired:

| #24 specification | #26 / PR #27 reality |
|-------------------|----------------------|
| 8-byte header with encoded plaintext length | 9-byte prefix; **no length field** |
| Flags byte with reusable-DEK bit and epoch in bits 3–7 | **Single-byte epoch**; no flags; no DEK mode |
| `u16` channel | **`u32` channel** |
| Reusable-DEK broadcast with shared body + per-recipient envelope | **N independent AES-GCM seals** per broadcast |
| Rust `paxe-core` @ 4bb307c alignment | Superseded by cluster-PSK-only design in this repo |

**Implication of #26:** Implementing #24 would **revert** the merged design. Close as superseded, not as "won't fix."

---

### #25 — `Replace SRP6a with out-of-band TLS 1.3 external PSK + ECDHE provisioning`

**Status: close SRP-removal portion as duplicate of #26; open new issue for TLS (see below).**

| #25 requirement | After PR #27 |
|-----------------|--------------|
| Delete `SRPUtils`, `SessionKeyManager`, verifiers, SRP tests | ✅ Done |
| Remove channel-0 SRP dispatch and pending queues | ✅ Done |
| TLS 1.3 external-PSK + `psk_dhe_ke` + X25519 provisioner | ❌ Not implemented |
| Bouncy Castle `bctls-jdk18on` behind provisioner boundary | ❌ Not implemented |
| Exporter derives **pairwise** 32-byte PAXE PSK per node pair | ❌ **Rejected** — cluster PSK model |
| Protect reusable-DEK frames from #24 | ❌ N/A — DEK mode removed |

**Implication of #26:** SRP6a is gone. TLS provisioning is still desired, but must follow the **cluster-PSK** model documented in `trex-paxe/README.md` (TLS transports or installs the shared cluster key; it does not derive per-pair data keys).

---

## Recommended GitHub actions

> **Note:** The cloud agent token cannot post issue comments on this repository. Use the suggested comments below when closing issues.

### Close as duplicate of #26

```
#20, #23, #24, #25
```

Suggested closure comment (adapt per issue):

> Superseded by #26, implemented in PR #27. The cluster-PSK-only PAXE design retired [pairwise SRP / reusable-DEK / Crypto.java DEK docs / pairwise TLS exporter — pick relevant phrase]. Closing as duplicate of #26.

### Leave open

```
#22
```

No closure. Optionally note that PR #27 did not affect trex-lib commit-announcement behaviour.

### Open one new issue

Use the body in the next section. It supersedes the **TLS provisioning** intent of #25 and the **demo / operational** follow-through that none of the closed issues capture.

After opening it, close **#25** (and optionally #23) as duplicate of the new issue **in addition to** #26, if you want the new issue to be the tracking home for TLS work.

---

## Proposed new issue

**Title:** `Add TLS 1.3 external-PSK control-plane provisioner for cluster PSK distribution`

**Supersedes:** #25 (TLS provisioning; SRP removal already done in #26). Optionally also tracks demo/ops work that #23 implied but did not specify.

**Depends on:** #26 (merged) — cluster-PSK PAXE data plane is fixed.

### Body (copy-paste ready)

```markdown
## Context

#26 / PR #27 adopted a **cluster-PSK-only** PAXE data plane:

- one 32-byte AES-256 cluster key per epoch (`ClusterKeyManager`);
- 9-byte prefix `BE16(fromId) || BE16(toId) || BE32(channel) || epoch`;
- no in-band key establishment, no SRP6a, no reusable-DEK mode.

SRP6a and RFC 5054 code are already removed. This issue adds an **optional control-plane** provisioner that uses TLS 1.3 external pre-shared key authentication plus ephemeral Diffie-Hellman to **distribute or rotate the cluster PSK** to members. The provisioner is outside the PAXE UDP codec and must not appear on the PAXE wire.

This supersedes #25. Unlike #25, the exporter/install step delivers the **same cluster PSK to every member** for a given epoch; it does **not** derive pairwise per-node-pair data keys.

## Standards terminology (RFC 8446)

- External pre-shared key authentication (bootstrap credential).
- Key-exchange mode `psk_dhe_ke` only — reject PSK-only `psk_ke`.
- Ephemeral X25519 key share for forward secrecy on the provisioning channel.
- TLS 1.3 AEAD cipher suites (e.g. `TLS_AES_128_GCM_SHA256`) are independent of the PSK key-exchange mode.
- Disable 0-RTT application data on the provisioning connection.

Do not document a nonexistent `TLS_PSK_WITH_ECDHE` TLS 1.3 cipher suite.

## Architecture

- **Data plane (unchanged):** PAXE UDP frames sealed with the cluster PSK from `ClusterKeyManager`.
- **Control plane (this issue):** TLS 1.3 connection authenticated with a bootstrap external PSK; after handshake, install the cluster PSK for the requested epoch into `ClusterKeyManager` on each node.
- The bootstrap PSK authenticates the provisioning channel only. It must **not** be installed as the PAXE cluster data key.
- A failed provisioner leaves the cluster key unavailable; `PaxeNetwork` must not start TLS from `seal`/`open`.
- Provisioning may be pairwise (operator connects to each node) or fan-out from a coordinator; the **installed material is identical** on all members for the epoch.

## Java implementation notes

JDK JSSE does not expose a portable external-PSK API. Add Bouncy Castle TLS (`bctls-jdk18on`, per repository dependency conventions) behind a `ClusterKeyProvisioner` (name TBD) interface. Keep Bouncy Castle types out of `PaxePacket` / `PaxeNetwork`.

Suggested BC APIs:

- `TlsPSKExternal` / `BasicTlsPSKExternal`
- `TlsPeer.getPskKeyExchangeModes()` → only `PskKeyExchangeMode.psk_dhe_ke`
- X25519 supported group + key share
- `TlsContext.exportKeyingMaterial(...)` if key derivation is required for the provisioning protocol

Exact exporter label and context bytes are **TBD in the PR**; they must bind `{clusterId, epoch}` (and optionally installer identity), not an unordered node pair as in #25.

## Demo and operator UX

Update `ClusterStackAdmin` and any cluster demo / test harness so that:

1. A coordinator can generate a fresh 32-byte cluster PSK for an epoch.
2. Members receive it via the TLS provisioner (not manual `set-psk <hex>` copy/paste), while tests may still inject keys directly.
3. Epoch rotation uses the same provisioner to install the next epoch alongside the previous one.

Manual hex PSK sharing remains acceptable for local dev but should not be the only documented path once this lands.

## Cleanup

- Remove stale references to `SessionKeyManager` in unrelated `trex-lib` test comments.
- Confirm zero remaining SRP / RFC 5054 / verifier / reusable-DEK references outside historical git history.
- Document the separation: bootstrap PSK → TLS provisioning channel → cluster PSK → PAXE UDP frames.

## Acceptance criteria

- [ ] Client and server with the same bootstrap external PSK complete TLS 1.3 with `psk_dhe_ke` and X25519.
- [ ] After provisioning, all members hold the **same** 32-byte cluster PSK in `ClusterKeyManager` for the target epoch.
- [ ] PAXE `seal`/`open` round-trip succeeds across the cluster using the provisioned key.
- [ ] Wrong bootstrap PSK, unknown identity, PSK-only mode, missing X25519 share, TLS downgrade, and exporter/context mismatch each fail without mutating installed cluster keys.
- [ ] No PAXE UDP datagram or application channel carries TLS or provisioning payloads.
- [ ] 0-RTT disabled; no application bytes before handshake complete.
- [ ] Demo / admin tooling uses the provisioner; documentation describes bootstrap PSK vs cluster data key vs epoch rotation.
- [ ] No SRP6a, RFC 5054, verifier, or reusable-DEK code paths reintroduced.
```

---

## Summary

| Action | Issues |
|--------|--------|
| **Close as duplicate of #26** | #20, #23, #24, #25 |
| **Leave open** | #22 |
| **Open new issue** | TLS 1.3 cluster-PSK provisioner + demo updates (body above) |

Four of five open issues are obsolete or fully addressed by the cluster-PSK redesign. One (#22) is an independent Paxos documentation task. TLS-based key distribution is the only substantial security follow-up; it must target **cluster PSK transport**, not the pairwise exporter design in #25.
