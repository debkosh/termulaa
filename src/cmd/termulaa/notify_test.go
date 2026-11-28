package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"filippo.io/age"
)

// open is what the phone app does: base64, age-decrypt, JSON.
func open(t *testing.T, id *age.X25519Identity, body string) notifyMessage {
	t.Helper()
	raw, err := base64.StdEncoding.DecodeString(body)
	if err != nil {
		t.Fatalf("body is not base64: %v", err)
	}
	r, err := age.Decrypt(bytes.NewReader(raw), id)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	plain, _ := io.ReadAll(r)
	var m notifyMessage
	if err := json.Unmarshal(plain, &m); err != nil {
		t.Fatalf("plaintext is not JSON: %v", err)
	}
	return m
}

func TestNotifySendsWhatTheAppCanOpen(t *testing.T) {
	id, _ := age.GenerateX25519Identity()
	var got []string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if r.Method != http.MethodPost || r.URL.Path != "/abcdefghijklmnopqrstuvwx" {
			t.Errorf("got %s %s", r.Method, r.URL.Path)
		}
		got = append(got, string(b))
	}))
	defer srv.Close()
	saved := http.DefaultTransport
	http.DefaultTransport = srv.Client().Transport // trust the test TLS cert
	t.Cleanup(func() { http.DefaultTransport = saved })

	dir := t.TempDir()
	t.Setenv("KEYKOSH_ROOT", dir)
	t.Setenv("TMUX", "")
	var out, errb bytes.Buffer
	line := srv.URL + "/abcdefghijklmnopqrstuvwx " + id.Recipient().String()
	if code := runClient([]string{"notify", "setup", line}, &out, &errb); code != 0 {
		t.Fatalf("setup exit %d: %s", code, errb.String())
	}
	if fi, err := os.Stat(filepath.Join(dir, "termulaa", "notify.env")); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("notify.env: %v %v", fi, err)
	}

	long := strings.Repeat("x", notifyTitleMax+1)
	if code := runClient([]string{"notify", "decision", long}, &out, &errb); code != exitUsage || !strings.Contains(errb.String(), fmt.Sprintf("limit is %d", notifyTitleMax)) {
		t.Fatalf("an over-long title: exit %d, %q", code, errb.String())
	}
	title := strings.Repeat("é", notifyTitleMax-4) + " yes?"
	title = string([]rune(title)[:notifyTitleMax])
	if code := runClient([]string{"notify", "decision", title, "-from", "release", "-link", "https://claude.ai/code/session_1"}, &out, &errb); code != 0 {
		t.Fatalf("notify exit %d: %s", code, errb.String())
	}
	if code := runClient([]string{"notify", "-cancel", "release"}, &out, &errb); code != 0 {
		t.Fatalf("cancel exit %d: %s", code, errb.String())
	}
	if len(got) != 2 {
		t.Fatalf("want 2 posts, got %d", len(got))
	}
	m := open(t, id, got[0])
	if m.V != 1 || m.Level != "decision" || m.From != "release" || m.Key != "release" || m.Link != "https://claude.ai/code/session_1" {
		t.Errorf("message: %+v", m)
	}
	if m.Title != title {
		t.Errorf("title changed on the way: %q", m.Title)
	}
	if c := open(t, id, got[1]); c.Cancel != "release" || c.Level != "" {
		t.Errorf("cancel: %+v", c)
	}
	if len(got[0]) > 4096 {
		t.Errorf("body %d bytes, over ntfy's 4096", len(got[0]))
	}

	// Someone without the key reads nothing.
	other, _ := age.GenerateX25519Identity()
	raw, _ := base64.StdEncoding.DecodeString(got[0])
	if _, err := age.Decrypt(bytes.NewReader(raw), other); err == nil {
		t.Error("a different key decrypted the message")
	}
}

func TestNotifyMaxBodyFitsNtfy(t *testing.T) {
	id, _ := age.GenerateX25519Identity()
	m := notifyMessage{V: 1, Level: "info", From: strings.Repeat("f", 64), Key: strings.Repeat("k", 64),
		Title: strings.Repeat("é", notifyTitleMax), Body: clipBytes(strings.Repeat("é", notifyBodyMax), notifyBodyMax),
		Link: "https://claude.ai/code/session_01UWWzL1z6oZ6YZQ1zt44y42"}
	s, err := sealNotify(m, id.Recipient().String())
	if err != nil {
		t.Fatal(err)
	}
	if len(s) > 4096 {
		t.Errorf("largest message is %d bytes, over ntfy's 4096", len(s))
	}
}

func TestNotifyUsage(t *testing.T) {
	var out, errb bytes.Buffer
	for _, args := range [][]string{
		{"notify"}, {"notify", "loud", "x"}, {"notify", "info"}, {"notify", "info", "  "},
		{"notify", "setup", "https://ntfy.sh/short age1x"}, {"notify", "setup", "http://ntfy.sh/abcdefghijklmnopqrstuvwx age1x"},
	} {
		if code := runClient(args, &out, &errb); code == 0 {
			t.Errorf("%v: exit 0", args)
		}
	}
}
