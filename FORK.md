# qWDTT Fork

This fork starts from upstream stable `a296c57` (qWDTT 1.4.4). Its package is
`net.qwdtt.client.fork`, shown as **qWDTT Fork**. Releases and in-app update checks
use [this fork](https://github.com/avanturist888/proxy-turn-vk-android/releases).
It installs alongside upstream; it cannot update an APK signed by another key.

## Migration and Android settings

1. Export connection profiles from the old app, then import them into the fork.
   Keep the original app and its data until the fork works. Profile files contain
   connection secrets; keep them private. Account login and global settings may
   need to be configured separately.
2. Connect once and grant Android VPN permission. Verify a working saved server.
3. Open **Exceptions → Android VPN settings**, select **qWDTT Fork**, and enable
   **Always-on VPN** and **Block connections without VPN**. Only Android/the user
   can grant this system policy; installing the app alone does not enable it.
4. With these settings, apps wait during recovery instead of falling back to the
   mobile/Wi-Fi connection. System always-on starts try saved servers in order.
   A manual connection is also supervised: its exact parameters are tried first,
   followed by saved profiles after the selected profile, wrapping to the start.
   Enabling always-on on an already connected VPN retains this recovery loop.

By default, all application IPv4, IPv6 and DNS traffic enters the VPN. Imported
app/site exclusions are ignored until **Allow selected exclusions directly** is
explicitly enabled. There is no automatic Russian-site classification. Optional
site rules use the existing IPv4 route exclusions; IPv6 stays captured. Excluded
apps can use the direct network only when Android lockdown is disabled. Disabling
lockdown also weakens protection during VPN outages; always-on alone is not a
kill switch. Domain rules can affect unrelated domains sharing an IP address.

IPv6 is captured even with an IPv4-only server: it may be unavailable through
such a server, but does not fall back directly. Android platform traffic has
system-defined VPN exceptions; this is application traffic protection, not a
claim that every packet emitted by the operating system is inside the tunnel.
VPN transport/control sockets (VK/TURN, transport DNS/DoH, WireGuard loopback)
must reach the underlying network to build the tunnel. These narrowly protected
sockets do not exclude the app's or VK's entire UID from VPN.

Account sign-in, account TURN renewal and CAPTCHA use Android WebView, whose
individual sockets cannot be protected by this app. During these control flows
the fork temporarily releases an existing WireGuard interface, keeps Android's
lockdown policy enabled, and restores the saved interface afterwards. Android
permits the VPN application's own UID to bootstrap while ordinary apps remain
blocked by lockdown. This interval therefore requires **Block connections without
VPN** for fail-closed behavior; without it, other apps can use the direct network.
Normal anonymous recovery keeps the interface when no WebView is needed. An
advisory application DNS probe cannot veto transport startup: the protected Go
resolver makes the actual bootstrap queries. Control flows and network recovery
are serialized, and Stop prevents a stale flow from restoring the old interface.

## Recovery

Network/address changes restart the outer transport while retaining the VPN
interface where possible. TCP checks bind explicitly to the current Android VPN
network and reject a missing/changed VPN, so the VPN owner's underlay exemption
cannot produce a false success. These checks detect a dead path
even if local statistics keep arriving. Two failed checks trigger recovery;
success clears the failure streak. Repeated failures are rate limited. Recovery
does not require Android's Internet-validation probe to succeed, and continues
with the screen off. Unattended attempts have a hard 60-second limit and control
WebViews a 20-second limit. The first explicit connection allows up to 180 seconds
overall and 120 seconds for interactive sign-in. Deadlines never extend while
CAPTCHA or account login repeats. Established sessions also abandon stalled
control/reconnect work within these unattended limits and try the next server.
The list is retried after a 60-second cooldown; automatic attempts do not keep
opening interactive sign-in or manual CAPTCHA. Wrong credentials need correction.

Stop revokes the current session before cancelling work. The next attempt waits
for its predecessor's teardown; buffered output and delayed callbacks retain the
process/session identity that produced them. Warmed VPN services are released
even when startup fails before an interface exists. Sticky restarts resume only
a persisted connection request or Android's system always-on request; explicit
Stop clears the persisted request.

Settings reloads retain their source configuration and session identity; terminal
teardown cancels and joins them before closing WireGuard. Internal service warmup
does not request a connection, even when Android always-on is enabled. A newly
accepted manual command retires the previous recovery loop before loading settings.
Manual parameters are tried first, followed by every saved candidate: matching only
a host/password does not remove a saved profile with different ports or hashes.
Reader retirement includes its control-flow children and captured subprocess;
replacement publication waits for them before reusing the VPN. Applying a saved
profile commits connection fields, encrypted password and selected identity in
one settings transaction, including when recovery is cancelled.

Network availability belongs to the requested connection, not to the lifetime
of its subprocess. The same recovery loop waits while offline and starts a fresh
attempt after network loss/return, including during server cooldown. Terminal
Stop clears reconnect and retained-VPN state before retiring old jobs. Transport
sockets are individually protected; Android selects the physical network without
an application-level Wi-Fi preference or a process-wide network binding.
Bootstrap does not require validation under lockdown. A healthy VPN stays up
through unrelated network changes; VPN reachability detects a broken path.
Stabilization has a fixed delay rather than waiting for every network to settle.
Requested connection state keeps Stop available while offline or retrying.
This does not bypass the VPN for other application sockets.

## Integration and validation

The old `network-recovery` branch is retained, including its previous fixes.
This branch uses the modern stable transport and keeps the relevant recovery
invariants: real downlink activity, no screen-off recovery gate, no dependence
on validated connectivity, and preserved VPN during transport recovery. It does
not require a new server PONG protocol. New upstream work should be fetched and
reviewed against these invariants before merging; stable releases are the base.

Build on Linux with Java 17, Android SDK 35, an installed Android NDK
(`ANDROID_NDK_HOME`), and Go as declared by `go_client/go.mod`:

```sh
cd go_client && go test ./... && cd ..
./gradlew -PtargetAbis=arm64-v8a testDebugUnitTest assembleRelease
```

Tests use synthetic recovery inputs and a local Unix descriptor-passing fixture;
they do not read profiles or connect to a real VPN. APKs must retain the fork's
signing key between releases. Before trusting a release, device checks must
cover always-on after reboot/process death, saved-server fallback, mobile/Wi-Fi
switches, a period without coverage and recovery with the screen off, plus DNS
and IPv6 egress and lockdown while transport is down. A successful build does
not establish those device results or compatibility with a particular server.
