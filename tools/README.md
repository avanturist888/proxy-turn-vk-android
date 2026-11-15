# Isolated worker build

`wk` is a thin wrapper for an installed shared worker runner, pinned by commit.
Copy `wk.conf.example` to ignored `wk.conf` and set the worker's dedicated paths.
The runner must be installed separately; no machine credentials are committed.

From Git Bash on the development machine:

```sh
bash tools/wk check --all-history
bash tools/wk init
bash tools/wk run --no-pg --no-deps -- powershell -NoProfile -File tools/worker-android.ps1
```

The worker needs Docker with a Linux engine. The image contains its own SDK,
NDK, JDK and Go. Only the source checkout and a project-specific cache volume
are mounted. Tests have private home/config/temp paths; no device, host SDK,
keystore or saved VPN profiles are used. The command is bounded to one hour.
The unsigned release APK is retrieved through `wk get` and signed separately;
private release keys never enter the worker image or checkout.
