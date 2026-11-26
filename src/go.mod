module github.com/debkosh/termulaa/src

go 1.25.0

require (
	github.com/creack/pty v1.1.24
	github.com/gorilla/websocket v1.5.3
	// smux multiplexes every -rc viewer connection over a few long-lived
	// outbound tunnels; hand-rolling stream framing would be riskier code.
	github.com/xtaci/smux v1.5.34
)

require (
	// age encrypts `termulaa notify` messages to the phone app's key, so the
	// public ntfy server carries only ciphertext; never hand-roll that.
	filippo.io/age v1.3.2
	github.com/sudiptadeb/amarit v0.3.1
)

require (
	filippo.io/hpke v0.4.0 // indirect
	golang.org/x/crypto v0.55.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
)
