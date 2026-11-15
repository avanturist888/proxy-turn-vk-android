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
   mobile/Wi-Fi connection. System always-on starts try saved servers in order,
   use the first working path, and keep retrying the list after failures.

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

## Recovery

Network/address changes restart the outer transport while retaining the VPN
interface where possible. End-to-end application TCP checks detect a dead path
even if local statistics keep arriving. Two failed checks trigger recovery;
success clears the failure streak. Repeated failures are rate limited. Recovery
does not require Android's Internet-validation probe to succeed, and continues
with the screen off. Server selection allows up to 60 seconds per saved server;
probes run every 10 seconds after connection. Wrong credentials or an unavailable
VK login still need correction; the fork cannot manufacture valid credentials.

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
