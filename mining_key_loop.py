"""
Alternating D/S key hold loop for mining automation.

Sequence (repeats forever):
  - Wait 5s on start
  - Hold D for 3s, wait 1s, press F
  - Wait 12 minutes
  - Hold S for 3s, wait 1s, press F
  - Wait 12 minutes
  - (repeat from D)

Press Esc to stop.
"""

import ctypes
import sys
import time
from datetime import datetime
from pynput import keyboard

INITIAL_DELAY_S = 5
HOLD_DURATION_S = 1.5
AFTER_HOLD_DELAY_S = 1
BETWEEN_CYCLES_S = 10 * 60  # 12 minutes
STATUS_INTERVAL_S = 1 * 60  # terminal countdown every 3 minutes
TAP_HOLD_S = 0.15

VK_D = 0x44
VK_S = 0x53
VK_F = 0x46
VK_W = 0x57
VK_A = 0x41

KEYEVENTF_KEYUP = 0x0002

running = True


def on_press(key):
    global running
    if key == keyboard.Key.esc:
        running = False
        return False


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


class GameKeyboard:
    """Windows keybd_event — reliable for games; clears WASD before each action."""

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise RuntimeError("GameKeyboard requires Windows")
        self.user32 = ctypes.windll.user32

    def _scan(self, vk: int) -> int:
        return self.user32.MapVirtualKeyW(vk, 0)

    def release_movement_keys(self) -> None:
        for vk in (VK_W, VK_A, VK_S, VK_D):
            self.user32.keybd_event(vk, self._scan(vk), KEYEVENTF_KEYUP, 0)

    def hold(self, vk: int, duration_s: float) -> None:
        self.release_movement_keys()
        time.sleep(0.05)
        scan = self._scan(vk)
        self.user32.keybd_event(vk, scan, 0, 0)
        try:
            time.sleep(duration_s)
        finally:
            self.user32.keybd_event(vk, scan, KEYEVENTF_KEYUP, 0)

    def tap(self, vk: int, hold_s: float = TAP_HOLD_S) -> None:
        self.release_movement_keys()
        time.sleep(0.05)
        scan = self._scan(vk)
        self.user32.keybd_event(vk, scan, 0, 0)
        time.sleep(hold_s)
        self.user32.keybd_event(vk, scan, KEYEVENTF_KEYUP, 0)


def wait_with_status(seconds: float, label: str) -> bool:
    global running
    start = time.monotonic()
    end = start + seconds
    next_log_at = start

    while running and time.monotonic() < end:
        now = time.monotonic()
        if now >= next_log_at:
            remaining = int(end - now)
            if remaining >= 60:
                log(f"{label} — {remaining // 60}m {remaining % 60}s left")
            else:
                log(f"{label} — {remaining}s left")
            next_log_at = now + STATUS_INTERVAL_S
        time.sleep(min(1.0, max(0, end - now)))
    return running


def run_cycle(kb: GameKeyboard, hold_vk: int, hold_label: str) -> bool:
    log(f"=== {hold_label} cycle === (keep game window focused)")
    log(f"Holding {hold_label} for {HOLD_DURATION_S}s")
    kb.hold(hold_vk, HOLD_DURATION_S)

    if not running:
        return False

    log(f"Waiting {AFTER_HOLD_DELAY_S}s")
    time.sleep(AFTER_HOLD_DELAY_S)

    if not running:
        return False

    log(f"Pressing F ({TAP_HOLD_S}s hold)")
    kb.tap(VK_F)

    next_label = "S" if hold_vk == VK_D else "D"
    return wait_with_status(BETWEEN_CYCLES_S, f"Waiting before {next_label}")


def main() -> None:
    global running

    if sys.platform != "win32":
        print("This script requires Windows.", file=sys.stderr)
        sys.exit(1)

    kb = GameKeyboard()
    listener = keyboard.Listener(on_press=on_press)
    listener.start()

    log("Mining key loop started. Press Esc to stop.")
    log(f"Initial delay: {INITIAL_DELAY_S}s — click your game window now")

    if not wait_with_status(INITIAL_DELAY_S, "Initial delay"):
        log("Stopped.")
        return

    use_d = True
    while running:
        if use_d:
            if not run_cycle(kb, VK_D, "D"):
                break
        else:
            if not run_cycle(kb, VK_S, "S"):
                break
        use_d = not use_d

    kb.release_movement_keys()
    listener.stop()
    log("Stopped.")


if __name__ == "__main__":
    main()
