package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func runExecJSON(t *testing.T, raw string) (map[string]any, time.Duration) {
	t.Helper()
	start := time.Now()
	v, err := runExec(context.Background(), json.RawMessage(raw))
	if err != nil {
		t.Fatalf("runExec(%s) error: %v", raw, err)
	}
	m, ok := v.(map[string]any)
	if !ok {
		t.Fatalf("runExec(%s) returned %T, want map", raw, v)
	}
	return m, time.Since(start)
}

// 后台孤儿持有 stdout 管道：shell 立即退出后 exec 不再调用 Cancel，
// 超时须仍能按时返回并杀掉孤儿
func TestExecBackgroundOrphanTimeout(t *testing.T) {
	m, elapsed := runExecJSON(t, `{"command":"sleep 30 & echo launched","timeout_ms":3000}`)
	if elapsed > 10*time.Second {
		t.Fatalf("took %v, far beyond the 3s timeout", elapsed)
	}
	if m["timed_out"] != true {
		t.Fatalf("timed_out = %v, want true", m["timed_out"])
	}
	if !strings.Contains(m["stdout"].(string), "launched") {
		t.Fatalf("stdout = %q, want it to contain launched", m["stdout"])
	}
}

// setsid 孤儿逃出进程组且持有管道：进程组击杀够不到，WaitDelay 须强制收敛
func TestExecSetsidOrphanTimeout(t *testing.T) {
	m, elapsed := runExecJSON(t, `{"command":"setsid sleep 30 & echo launched","timeout_ms":3000}`)
	if elapsed > 10*time.Second {
		t.Fatalf("took %v, far beyond the 3s timeout", elapsed)
	}
	if m["timed_out"] != true {
		t.Fatalf("timed_out = %v, want true", m["timed_out"])
	}
}

// setsid 挂后台（重定向 stdio 的标准 daemon 写法）是合法用法：
// 调用须立即返回，后台任务须继续存活，不能被超时击杀波及
func TestExecSetsidDaemonSurvives(t *testing.T) {
	pidfile := filepath.Join(t.TempDir(), "pid")
	cmd := fmt.Sprintf(`setsid sh -c 'echo $$ > %s; sleep 8' >/dev/null 2>&1 </dev/null & echo launched`, pidfile)
	m, elapsed := runExecJSON(t, `{"command":`+strconv.Quote(cmd)+`,"timeout_ms":30000}`)
	if elapsed > 5*time.Second {
		t.Fatalf("took %v, want immediate return", elapsed)
	}
	if m["timed_out"] != false || m["exit_code"] != 0 {
		t.Fatalf("timed_out=%v exit_code=%v, want false/0", m["timed_out"], m["exit_code"])
	}
	// 调用返回时后台任务刚 fork，pidfile 的写入有短暂延迟
	var data []byte
	for range 40 {
		var err error
		if data, err = os.ReadFile(pidfile); err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if len(data) == 0 {
		t.Fatalf("daemon never wrote %s", pidfile)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		t.Fatalf("bad pidfile content %q", data)
	}
	if err := syscall.Kill(pid, 0); err != nil {
		t.Fatalf("daemon pid %d not alive after exec returned: %v", pid, err)
	}
	_ = syscall.Kill(pid, syscall.SIGKILL)
}

func TestExecForegroundTimeout(t *testing.T) {
	m, elapsed := runExecJSON(t, `{"command":"sleep 30","timeout_ms":3000}`)
	if elapsed > 10*time.Second {
		t.Fatalf("took %v, far beyond the 3s timeout", elapsed)
	}
	if m["timed_out"] != true || m["exit_code"] == 0 {
		t.Fatalf("timed_out=%v exit_code=%v, want true/nonzero", m["timed_out"], m["exit_code"])
	}
}

func TestExecCompletesBeforeTimeout(t *testing.T) {
	m, elapsed := runExecJSON(t, `{"command":"echo hi","timeout_ms":30000}`)
	if elapsed > 10*time.Second {
		t.Fatalf("took %v, want fast return", elapsed)
	}
	if m["timed_out"] != false || m["exit_code"] != 0 {
		t.Fatalf("timed_out=%v exit_code=%v, want false/0", m["timed_out"], m["exit_code"])
	}
}
