package main

// `termulaa notify`: a notification to the phone app, end to end encrypted.
// The message is JSON, age-encrypted to the public key the app made, then
// base64, POSTed to the app's secret ntfy topic. The app holds the private
// key, so anyone who can send still cannot read. See docs/notify.md.

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"filippo.io/age"
)

// notifyMessage is the plaintext the app decrypts. Field names are the wire
// contract with android/…/notify/Message.kt.
type notifyMessage struct {
	V      int    `json:"v"`
	Level  string `json:"level,omitempty"`
	From   string `json:"from,omitempty"`
	Title  string `json:"title,omitempty"`
	Body   string `json:"body,omitempty"`
	Link   string `json:"link,omitempty"`
	Key    string `json:"key,omitempty"`
	Cancel string `json:"cancel,omitempty"`
}

// The limits keep the base64 ciphertext well under ntfy's 4096-byte message
// size, past which ntfy turns a message into an attachment.
const (
	notifyTitleMax = 200  // characters
	notifyBodyMax  = 1500 // bytes of UTF-8
)

var notifyLevels = map[string]bool{"info": true, "decision": true, "blocker": true}

// notifyConfig is notify.env: where to post and whom to encrypt to.
type notifyConfig struct {
	Endpoint  string // https://ntfy.sh/<topic>
	Recipient string // age1…
}

// notifyEnvPath is where notify.env lives: the keykosh folder when there is
// one, else ~/.termulaa.
func notifyEnvPath() string {
	if root := os.Getenv("KEYKOSH_ROOT"); root != "" {
		return filepath.Join(root, "termulaa", "notify.env")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".termulaa", "notify.env")
}

func loadNotifyConfig(path string) (notifyConfig, error) {
	f, err := os.Open(path)
	if err != nil {
		return notifyConfig{}, fmt.Errorf("%w (run `termulaa notify setup <line>` with the line the phone app shows)", err)
	}
	defer f.Close()
	var c notifyConfig
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, ok := strings.Cut(strings.TrimSpace(sc.Text()), "=")
		if !ok {
			continue
		}
		switch k {
		case "NOTIFY_ENDPOINT":
			c.Endpoint = v
		case "NOTIFY_RECIPIENT":
			c.Recipient = v
		}
	}
	if c.Endpoint == "" || c.Recipient == "" {
		return c, fmt.Errorf("%s: needs NOTIFY_ENDPOINT and NOTIFY_RECIPIENT", path)
	}
	return c, nil
}

// parseSetupLine reads the phone app's setup line: "<endpoint> <age1…>".
func parseSetupLine(line string) (notifyConfig, error) {
	parts := strings.Fields(line)
	if len(parts) != 2 {
		return notifyConfig{}, errors.New("the setup line is two words: the endpoint and the age key")
	}
	u, err := url.Parse(parts[0])
	if err != nil || u.Scheme != "https" || u.Host == "" || len(strings.Trim(u.Path, "/")) < 16 {
		return notifyConfig{}, fmt.Errorf("not an https topic URL: %q", parts[0])
	}
	if _, err := age.ParseX25519Recipient(parts[1]); err != nil {
		return notifyConfig{}, fmt.Errorf("not an age public key: %w", err)
	}
	return notifyConfig{Endpoint: parts[0], Recipient: parts[1]}, nil
}

// sealNotify encrypts m to the recipient and returns the base64 body.
func sealNotify(m notifyMessage, recipient string) (string, error) {
	r, err := age.ParseX25519Recipient(recipient)
	if err != nil {
		return "", err
	}
	plain, err := json.Marshal(m)
	if err != nil {
		return "", err
	}
	var out bytes.Buffer
	w, err := age.Encrypt(&out, r)
	if err != nil {
		return "", err
	}
	if _, err := w.Write(plain); err != nil {
		return "", err
	}
	if err := w.Close(); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(out.Bytes()), nil
}

func postNotify(endpoint, body string) error {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Post(endpoint, "text/plain", strings.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 300))
		return fmt.Errorf("%s answered %s: %s", endpoint, resp.Status, strings.TrimSpace(string(b)))
	}
	return nil
}

// defaultFrom is the tmux window name, else the host name.
func defaultFrom() string {
	if os.Getenv("TMUX") != "" {
		if out, err := exec.Command("tmux", "display", "-p", "#W").Output(); err == nil {
			if s := strings.TrimSpace(string(out)); s != "" {
				return s
			}
		}
	}
	h, _ := os.Hostname()
	return h
}

// clipBytes cuts s to at most n bytes on a character boundary.
func clipBytes(s string, n int) string {
	if len(s) <= n {
		return s
	}
	n -= len("…")
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n] + "…"
}

func runNotify(args []string, stdout, stderr io.Writer) int {
	if len(args) > 0 && args[0] == "setup" {
		return runNotifySetup(args[1:], stdout, stderr)
	}
	fs := newFlags("notify", stderr)
	body := fs.String("body", "", "")
	from := fs.String("from", "", "")
	link := fs.String("link", "", "")
	key := fs.String("key", "", "")
	cancel := fs.String("cancel", "", "")
	// Flags may follow the level and title: pull the two positionals out.
	var pos []string
	rest := args
	for len(rest) > 0 {
		if code, done := parse(fs, rest); done {
			return code
		}
		rest = fs.Args()
		if len(rest) > 0 {
			pos = append(pos, rest[0])
			rest = rest[1:]
		}
	}

	var m notifyMessage
	switch {
	case *cancel != "":
		if len(pos) != 0 {
			fmt.Fprint(stderr, helpFor("notify"))
			return exitUsage
		}
		m = notifyMessage{V: 1, Cancel: *cancel}
	case len(pos) == 2 && notifyLevels[pos[0]] && strings.TrimSpace(pos[1]) != "":
		// The title is read aloud whole; cutting it would drop the end of a
		// question ("…Yes?" heard as "Y"). Refuse instead, and say why.
		if n := len([]rune(strings.TrimSpace(pos[1]))); n > notifyTitleMax {
			fmt.Fprintf(stderr, "termulaa: the title is %d characters; the limit is %d, because it is spoken. Shorten it and put the details in -body.\n", n, notifyTitleMax)
			return exitUsage
		}
		m = notifyMessage{V: 1, Level: pos[0], Title: strings.TrimSpace(pos[1]),
			Body: clipBytes(*body, notifyBodyMax), From: *from, Link: *link, Key: *key}
		if m.From == "" {
			m.From = defaultFrom()
		}
		if m.Key == "" {
			m.Key = m.From
		}
	default:
		fmt.Fprint(stderr, helpFor("notify"))
		return exitUsage
	}

	c, err := loadNotifyConfig(notifyEnvPath())
	if err != nil {
		return fail(stderr, err)
	}
	sealed, err := sealNotify(m, c.Recipient)
	if err != nil {
		return fail(stderr, err)
	}
	if err := postNotify(c.Endpoint, sealed); err != nil {
		return fail(stderr, err)
	}
	return 0
}

func runNotifySetup(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, helpFor("notify"))
		return exitUsage
	}
	c, err := parseSetupLine(strings.Join(args, " "))
	if err != nil {
		return fail(stderr, err)
	}
	path := notifyEnvPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fail(stderr, err)
	}
	data := fmt.Sprintf("NOTIFY_ENDPOINT=%s\nNOTIFY_RECIPIENT=%s\n", c.Endpoint, c.Recipient)
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		return fail(stderr, err)
	}
	fmt.Fprintln(stdout, "wrote", path)
	return 0
}
