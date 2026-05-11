"""检测对话文件是否超阈值，自动触发 /compact。

两阶段策略：
  1. AttachConsole(父进程) + CONIN$ + WriteConsoleInputW (直接终端有效)
  2. 备用：写标记文件 .compact_needed (CLAUDE.md 指引下可用)
"""
import ctypes
import ctypes.wintypes
import datetime
import json
import time
import os
import sys

THRESHOLD = 500 * 1024
GROWTH_STEP = 500 * 1024
CHAR_DELAY_S = 0.08  # 逐字符注入延迟，防止 Node REPL 丢字
MARKER = os.path.join(os.path.dirname(__file__), ".last_compact")
LOG = os.path.join(os.path.dirname(__file__), ".compact_log")
FLAG = os.path.join(os.path.dirname(__file__), ".compact_needed")

GENERIC_WRITE = 0x40000000
FILE_SHARE_READ = 1
FILE_SHARE_WRITE = 2
OPEN_EXISTING = 3
KEY_EVENT = 1
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

kernel32 = ctypes.windll.kernel32


def log_msg(msg):
    try:
        ts = datetime.datetime.now().isoformat()
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(f"{ts} {msg}\n")
            f.flush()
    except OSError:
        pass


def get_parent_pid():
    ntdll = ctypes.windll.ntdll
    class PBI(ctypes.Structure):
        _fields_ = [
            ("Reserved1", ctypes.c_void_p),
            ("PebBaseAddress", ctypes.c_void_p),
            ("Reserved2", ctypes.c_void_p * 2),
            ("UniqueProcessId", ctypes.c_void_p),
            ("InheritedFromUniqueProcessId", ctypes.c_void_p),
        ]
    pbi = PBI()
    rl = ctypes.wintypes.ULONG(0)
    status = ntdll.NtQueryInformationProcess(
        ctypes.c_void_p(-1), 0, ctypes.byref(pbi),
        ctypes.sizeof(pbi), ctypes.byref(rl),
    )
    if status != 0:
        return None
    return ctypes.cast(pbi.InheritedFromUniqueProcessId, ctypes.c_void_p).value


def inject_keystrokes(text):
    """AttachConsole -> CONIN$ -> WriteConsoleInputW"""
    ppid = get_parent_pid()
    kernel32.FreeConsole()
    if not kernel32.AttachConsole(ppid):
        log_msg(f"AttachConsole FAIL pid={ppid}")
        return False

    hwnd = kernel32.GetConsoleWindow()
    h_conin = kernel32.CreateFileW(
        "CONIN$", GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        None, OPEN_EXISTING, 0, None,
    )

    if h_conin == INVALID_HANDLE_VALUE:
        log_msg(f"CONIN$ FAIL, hwnd={hwnd:#x}")
        kernel32.FreeConsole()
        return False

    class KEY_EVENT_RECORD(ctypes.Structure):
        _fields_ = [
            ("bKeyDown", ctypes.wintypes.BOOL),
            ("wRepeatCount", ctypes.wintypes.WORD),
            ("wVirtualKeyCode", ctypes.wintypes.WORD),
            ("wVirtualScanCode", ctypes.wintypes.WORD),
            ("uChar", ctypes.wintypes.WCHAR),
            ("dwControlKeyState", ctypes.wintypes.DWORD),
        ]
    class INPUT_RECORD(ctypes.Structure):
        _fields_ = [
            ("EventType", ctypes.wintypes.WORD),
            ("Event", KEY_EVENT_RECORD),
        ]

    ok = True
    for ch in text:
        vk = ord(ch)
        rec_down = INPUT_RECORD(KEY_EVENT, KEY_EVENT_RECORD(True, 1, vk, 0, ch, 0))
        rec_up = INPUT_RECORD(KEY_EVENT, KEY_EVENT_RECORD(False, 1, vk, 0, ch, 0))
        arr = (INPUT_RECORD * 2)(rec_down, rec_up)
        written = ctypes.wintypes.DWORD(0)
        if not kernel32.WriteConsoleInputW(h_conin, arr, 2, ctypes.byref(written)):
            ok = False
        time.sleep(CHAR_DELAY_S)

    kernel32.CloseHandle(h_conin)
    kernel32.FreeConsole()
    return ok


def get_transcript_path():
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR", "")
    if project_dir:
        files = [f for f in os.listdir(project_dir) if f.endswith(".jsonl")]
        if files:
            latest = max(files, key=lambda f: os.path.getmtime(os.path.join(project_dir, f)))
            return os.path.join(project_dir, latest)
    try:
        return json.loads(sys.stdin.read()).get("transcript_path", "")
    except (json.JSONDecodeError, KeyError):
        return ""


def check_and_inject():
    tp = get_transcript_path()
    log_msg(f"tp={tp}")
    if not tp or not os.path.exists(tp):
        log_msg("no transcript")
        return

    size = os.path.getsize(tp)
    log_msg(f"size={size} thr={THRESHOLD}")
    if size < THRESHOLD:
        # 不需要压缩，清理标记
        for p in [FLAG, MARKER]:
            try:
                os.remove(p)
            except OSError:
                pass
        return

    last_size = 0
    try:
        with open(MARKER) as f:
            last_size = int(f.read().strip())
    except Exception:
        pass
    log_msg(f"last={last_size}")

    if last_size > 0 and size < last_size:
        log_msg(f"cross-session: {size} < {last_size}")
        last_size = 0

    if last_size > 0 and size - last_size < GROWTH_STEP:
        log_msg(f"skip: {size - last_size} < {GROWTH_STEP}")
        return

    # 尝试按键注入（直接终端有效）
    log_msg("inject via CONIN$...")
    ok = inject_keystrokes("/compact\r")

    if ok:
        with open(MARKER, "w") as f:
            f.write(str(size))
            f.flush()
        log_msg(f"INJECTED via CONIN$, size={size} ({size/1024:.0f}KB)")
    else:
        # SSH 下无控制台，写标记文件留给 CLAUDE.md 处理
        with open(FLAG, "w") as f:
            f.write(str(size))
            f.flush()
        with open(MARKER, "w") as f:
            f.write(str(size))
            f.flush()
        log_msg(f"FALLBACK: flag written, size={size} ({size/1024:.0f}KB)")


if __name__ == "__main__":
    if "--hook" in sys.argv:
        try:
            check_and_inject()
        except Exception as e:
            log_msg(f"EXCEPTION: {e}")
        print(json.dumps({"continue": True}))
    else:
        inject_keystrokes("/compact\r")
