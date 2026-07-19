"""
Alternating D/S key hold loop for mining automation — desktop overlay UI.

Small always-on-top window (same colour scheme as the mobile overlay):
  - Play starts the loop, Stop halts it (Esc also stops while running)
  - Shows the current phase and a live countdown
  - Drag anywhere on the window to move it; X closes it

Sequence (repeats forever once started):
  - Wait 5s on start
  - Hold D for 1.5s, wait 1s, press F
  - Wait 10 minutes
  - Hold S for 1.5s, wait 1s, press F
  - Wait 10 minutes
  - (repeat from D)

Run with pythonw.exe (or the .vbs launcher) so no terminal shows.
"""

import ctypes
import sys
import threading
import time
import tkinter as tk

from pynput import keyboard

INITIAL_DELAY_S = 5
HOLD_DURATION_S = 1.5
AFTER_HOLD_DELAY_S = 1
BETWEEN_CYCLES_S = 10 * 60  # 10 minutes
TAP_HOLD_S = 0.15

VK_D = 0x44
VK_S = 0x53
VK_F = 0x46
VK_W = 0x57
VK_A = 0x41

KEYEVENTF_KEYUP = 0x0002

# ---- Mobile overlay colour scheme ----
BG_IDLE = "#0E2C57"
STROKE_IDLE = "#74BCFF"
BG_RUNNING = "#144030"
STROKE_RUNNING = "#66E088"
TEXT_MAIN = "#FFFFFF"
TEXT_DIM = "#B0CFFF"
TEXT_DIM_RUNNING = "#9AF0AA"


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


class MiningLoopWorker:
    """Runs the D/S/F loop on a background thread; UI polls phase/remaining."""

    def __init__(self) -> None:
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._phase = "Idle"
        self._countdown_end: float | None = None

    # ---- state shared with the UI ----

    def status(self) -> tuple[str, int | None]:
        with self._lock:
            phase = self._phase
            end = self._countdown_end
        remaining = None
        if end is not None:
            remaining = max(0, int(round(end - time.monotonic())))
        return phase, remaining

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def _set_phase(self, phase: str, countdown_s: float | None = None) -> None:
        with self._lock:
            self._phase = phase
            self._countdown_end = (
                time.monotonic() + countdown_s if countdown_s is not None else None
            )

    # ---- control ----

    def start(self) -> None:
        if self.is_running():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    # ---- loop ----

    def _wait(self, seconds: float, phase: str) -> bool:
        self._set_phase(phase, seconds)
        end = time.monotonic() + seconds
        while not self._stop.is_set():
            left = end - time.monotonic()
            if left <= 0:
                return True
            time.sleep(min(0.2, left))
        return False

    def _run(self) -> None:
        kb = GameKeyboard()
        try:
            if not self._wait(INITIAL_DELAY_S, "Click your game window"):
                return
            use_d = True
            while not self._stop.is_set():
                label = "D" if use_d else "S"
                vk = VK_D if use_d else VK_S

                self._set_phase(f"Holding {label}")
                kb.hold(vk, HOLD_DURATION_S)
                if self._stop.is_set():
                    return

                if not self._wait(AFTER_HOLD_DELAY_S, f"After {label} hold"):
                    return

                self._set_phase("Pressing F")
                kb.tap(VK_F)
                if self._stop.is_set():
                    return

                next_label = "S" if use_d else "D"
                if not self._wait(BETWEEN_CYCLES_S, f"Waiting before {next_label}"):
                    return
                use_d = not use_d
        finally:
            kb.release_movement_keys()
            self._set_phase("Idle")


class OverlayWindow:
    """Frameless, draggable, always-on-top control window."""

    def __init__(self) -> None:
        self.worker = MiningLoopWorker()
        self.esc_listener: keyboard.Listener | None = None

        self.root = tk.Tk()
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.configure(bg=STROKE_IDLE)
        self.root.geometry("+80+80")

        # Border effect: outer frame is the stroke, inner frame the fill.
        self.frame = tk.Frame(self.root, bg=BG_IDLE)
        self.frame.pack(padx=2, pady=2, fill="both", expand=True)

        top = tk.Frame(self.frame, bg=BG_IDLE)
        top.pack(fill="x", padx=8, pady=(6, 0))
        self.title_label = tk.Label(
            top, text="Mining Loop", fg=TEXT_MAIN, bg=BG_IDLE,
            font=("Segoe UI", 10, "bold")
        )
        self.title_label.pack(side="left")
        self.close_btn = tk.Label(
            top, text="\u2715", fg=TEXT_DIM, bg=BG_IDLE,
            font=("Segoe UI", 10, "bold"), cursor="hand2"
        )
        self.close_btn.pack(side="right")
        self.close_btn.bind("<Button-1>", lambda _e: self.quit())

        self.phase_label = tk.Label(
            self.frame, text="Idle", fg=TEXT_DIM, bg=BG_IDLE,
            font=("Segoe UI", 10)
        )
        self.phase_label.pack(padx=12, pady=(6, 0))

        self.timer_label = tk.Label(
            self.frame, text="--:--", fg=TEXT_MAIN, bg=BG_IDLE,
            font=("Segoe UI", 22, "bold")
        )
        self.timer_label.pack(padx=12)

        self.toggle_btn = tk.Button(
            self.frame, text="\u25B6  Play", command=self.toggle,
            fg=TEXT_MAIN, bg="#1A4178", activebackground="#245290",
            activeforeground=TEXT_MAIN, relief="flat", cursor="hand2",
            font=("Segoe UI", 11, "bold"), padx=18, pady=4, borderwidth=0,
            highlightthickness=1, highlightbackground=STROKE_IDLE
        )
        self.toggle_btn.pack(padx=12, pady=(4, 10))

        # Drag anywhere (except the button/close, which handle clicks).
        for widget in (self.root, self.frame, top, self.title_label,
                       self.phase_label, self.timer_label):
            widget.bind("<Button-1>", self._drag_start)
            widget.bind("<B1-Motion>", self._drag_move)

        self._drag_x = 0
        self._drag_y = 0
        self._tick()

    # ---- dragging ----

    def _drag_start(self, event: tk.Event) -> None:
        self._drag_x = event.x_root - self.root.winfo_x()
        self._drag_y = event.y_root - self.root.winfo_y()

    def _drag_move(self, event: tk.Event) -> None:
        self.root.geometry(
            f"+{event.x_root - self._drag_x}+{event.y_root - self._drag_y}"
        )

    # ---- control ----

    def toggle(self) -> None:
        if self.worker.is_running():
            self.worker.stop()
        else:
            self.worker.start()
            self._start_esc_listener()

    def _start_esc_listener(self) -> None:
        if self.esc_listener is not None:
            return

        def on_press(key: object) -> None:
            if key == keyboard.Key.esc and self.worker.is_running():
                self.worker.stop()

        self.esc_listener = keyboard.Listener(on_press=on_press)
        self.esc_listener.start()

    def quit(self) -> None:
        self.worker.stop()
        if self.esc_listener is not None:
            self.esc_listener.stop()
        self.root.destroy()

    # ---- UI refresh ----

    def _apply_theme(self, running: bool) -> None:
        bg = BG_RUNNING if running else BG_IDLE
        stroke = STROKE_RUNNING if running else STROKE_IDLE
        dim = TEXT_DIM_RUNNING if running else TEXT_DIM
        self.root.configure(bg=stroke)
        for widget in (self.frame, self.title_label, self.phase_label,
                       self.timer_label, self.close_btn):
            widget.configure(bg=bg)
        self.title_label.master.configure(bg=bg)
        self.phase_label.configure(fg=dim)
        self.close_btn.configure(fg=dim)
        if running:
            self.toggle_btn.configure(
                text="\u25A0  Stop", bg="#1C5A3C", activebackground="#27714D",
                highlightbackground=stroke
            )
        else:
            self.toggle_btn.configure(
                text="\u25B6  Play", bg="#1A4178", activebackground="#245290",
                highlightbackground=stroke
            )

    def _tick(self) -> None:
        running = self.worker.is_running()
        phase, remaining = self.worker.status()
        self._apply_theme(running)
        self.phase_label.configure(text=phase if running else "Idle — Esc stops too")
        if running and remaining is not None:
            self.timer_label.configure(
                text=f"{remaining // 60:02d}:{remaining % 60:02d}"
            )
        elif running:
            self.timer_label.configure(text="\u2022 \u2022 \u2022")
        else:
            self.timer_label.configure(text="--:--")
        self.root.after(200, self._tick)

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    if sys.platform != "win32":
        print("This script requires Windows.", file=sys.stderr)
        sys.exit(1)
    OverlayWindow().run()


if __name__ == "__main__":
    main()
