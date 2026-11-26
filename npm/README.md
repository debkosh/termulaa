# termulaa

Terminal + coding agents in a browser tab, next to the webapp you're building. Persistent sessions, tabs, pane splits. Loopback-only Go binary.

```sh
npm install -g termulaa      # or run it once: npx termulaa ...
termulaa
```

The first run downloads the termulaa release this package names from GitHub, checks it against the
sha256 pinned in the package, and installs it to `~/.local/bin/termulaa`, where the
[install script](https://github.com/debkosh/termulaa/blob/main/install.sh) puts it. From then on
the binary there (or in `/usr/local/bin`) is the one that runs, and it keeps itself current.

Everything else: https://github.com/debkosh/termulaa
