#!/usr/bin/env python3
"""
AntiJail
Runs without terminal output for desktop shortcut use
"""

import tkinter as tk
from tkinter import ttk, font as tkfont
import threading
import time
import pyautogui
from PIL import Image, ImageTk, ImageGrab
from pynput import keyboard
import sys
import winsound
import subprocess
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

try:
    import pygame
    PYGAME_AVAILABLE = True
except ImportError:
    PYGAME_AVAILABLE = False

try:
    import cv2
    import numpy as np
    FIRE_DETECTION_AVAILABLE = True
except ImportError:
    FIRE_DETECTION_AVAILABLE = False

# Set True to auto-run home sequence when fire is detected; False = fire code kept but not used.
FIRE_AUTO_HOME_ENABLED = False

# --- Virtual mouse: optional; send clicks/drags to window without moving real cursor (Windows only) ---
# If ctypes/Windows API fails, virtual mouse is disabled and app still runs with real cursor.
_virtual_mouse_ok = False

def _virtual_click_impl(screen_x, screen_y):
    return False

def _virtual_drag_impl(screen_x1, screen_y1, screen_x2, screen_y2, duration=0.2):
    return False

def _virtual_mouse_available():
    return _virtual_mouse_ok

def virtual_click(screen_x, screen_y):
    if not _virtual_mouse_ok:
        return False
    try:
        return _virtual_click_impl(screen_x, screen_y)
    except Exception:
        return False

def virtual_drag(screen_x1, screen_y1, screen_x2, screen_y2, duration=0.2):
    if not _virtual_mouse_ok:
        return False
    try:
        return _virtual_drag_impl(screen_x1, screen_y1, screen_x2, screen_y2, duration)
    except Exception:
        return False

def _init_virtual_mouse():
    """Initialize virtual mouse on Windows. Safe to call; sets _virtual_mouse_ok."""
    global _virtual_mouse_ok
    if sys.platform != 'win32':
        return
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
        class POINT(ctypes.Structure):
            _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

        def get_window_and_client_point(screen_x, screen_y, hwnd=None):
            pt = POINT(int(screen_x), int(screen_y))
            if hwnd is None:
                hwnd = user32.WindowFromPoint(pt)
            if not hwnd:
                return None, 0, 0
            if not user32.ScreenToClient(hwnd, ctypes.byref(pt)):
                return None, 0, 0
            return hwnd, pt.x, pt.y

        def click_impl(sx, sy):
            hwnd, cx, cy = get_window_and_client_point(sx, sy)
            if not hwnd:
                return False
            WM_LBUTTONDOWN, WM_LBUTTONUP = 0x0201, 0x0202
            MK_LBUTTON = 0x0001
            lparam = (int(cx) & 0xFFFF) | ((int(cy) & 0xFFFF) << 16)
            user32.PostMessageW(hwnd, WM_LBUTTONDOWN, MK_LBUTTON, lparam)
            user32.PostMessageW(hwnd, WM_LBUTTONUP, 0, lparam)
            return True

        def drag_impl(sx1, sy1, sx2, sy2, duration=0.2):
            """Virtual drag with multiple intermediate moves so apps register it better."""
            hwnd, c1x, c1y = get_window_and_client_point(sx1, sy1)
            if not hwnd:
                return False
            _, c2x, c2y = get_window_and_client_point(sx2, sy2, hwnd=hwnd)
            WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MOUSEMOVE = 0x0201, 0x0202, 0x0200
            MK_LBUTTON = 0x0001
            lparam_down = (int(c1x) & 0xFFFF) | ((int(c1y) & 0xFFFF) << 16)
            user32.PostMessageW(hwnd, WM_LBUTTONDOWN, MK_LBUTTON, lparam_down)
            # Break drag into small steps along the line
            total = max(duration, 0.1)
            steps = max(int(total / 0.03), 3)
            for i in range(1, steps + 1):
                t = i / steps
                cx = int(c1x + (c2x - c1x) * t)
                cy = int(c1y + (c2y - c1y) * t)
                lparam_move = (int(cx) & 0xFFFF) | ((int(cy) & 0xFFFF) << 16)
                user32.PostMessageW(hwnd, WM_MOUSEMOVE, MK_LBUTTON, lparam_move)
                time.sleep(total / steps)
            lparam_up = (int(c2x) & 0xFFFF) | ((int(c2y) & 0xFFFF) << 16)
            user32.PostMessageW(hwnd, WM_LBUTTONUP, 0, lparam_up)
            return True

        global _virtual_click_impl, _virtual_drag_impl
        _virtual_click_impl = click_impl
        _virtual_drag_impl = drag_impl
        _virtual_mouse_ok = True
    except Exception:
        _virtual_mouse_ok = False
        pass

# Home sequence: = key for X,Y. HOME_OFFSET = global shift. Per-emu: HOME_SEQUENCE_BY_EMULATOR[emu_id].
HOME_OFFSET = (0, 0)
HOME_Y_OFFSET_PER_EMULATOR = 255
HOME_DRAG_DURATION = 0.2
HOME_DRAG_DURATION_RIGHT_TO_LEFT = 0.7
HOME_DELAY = 0.8
HOME_DELAY_LONG = 1.1
HOME_SEQUENCE_BY_EMULATOR = {
    1: [
        ("pause_click",),
        ("drag", 49, 210, 107, 210),
        ("click_with_delay", 414, 40, 0.4),
        ("click", 414, 40),
        ("click", 33, 68),
        ("click", 209, 90),
        ("delay_long",),
        ("click", 240, 52),
        ("delay_long",),
        ("click", 395, 152),
        ("click", 291, 73),
        ("delay_long",),
        ("drag", 415, 42, 342, 42), ("delay", 0.5),
        ("click", 387, 42), ("delay", 3),
        ("drag", 415, 42, 342, 42), ("delay", 0.5),
        ("click", 387, 42), ("delay", 2),
        ("click_with_delay", 414, 69, 0.4),
        ("click", 414, 69),
        ("click_with_delay", 35, 91, 0.2),
        ("click", 35, 91),
        ("delay_long",),
        ("drag", 95, 210, 20, 210), ("delay", 0.3),
        ("drag", 55, 210, 70, 210), ("delay", 0.3),
        ("drag", 95, 210, 20, 210), ("delay", 0.3),
        ("pause_click",),
    ],
    2: [
        ("pause_click",),
        ("click_with_delay", 416, 297, 0.4),
        ("click", 416, 297),
        ("click", 30, 322),
        ("click", 209, 345),
        ("delay_long",),
        ("click", 240, 307),
        ("delay_long",),
        ("click", 395, 407),
        ("click", 291, 328),
        ("delay_long",),
        ("drag", 415, 295, 340, 295), ("delay", 0.5),
        ("click", 387, 295), ("delay", 3),
        ("drag", 415, 295, 340, 295), ("delay", 0.5),
        ("click", 387, 295), ("delay", 2),
        ("click_with_delay", 413, 324, 0.4),
        ("click", 413, 324),
        ("click_with_delay", 30, 346, 0.2),
        ("click", 30, 346),
        ("delay_long",),
        ("drag", 65, 465, 72, 465), ("delay", 0.4),
        ("drag", 95, 465, 18, 465), ("delay", 0.4),
        ("pause_click",),
    ],
    3: [
        ("pause_click",),
        ("drag", 49, 720, 107, 720),
        ("click", 414, 543),
        ("click_with_delay", 414, 543, 0.4),
        ("click", 414, 543),
        ("click", 29, 569),
        ("click", 205, 590),
        ("delay_long",),
        ("click", 232, 551),
        ("delay_long",),
        ("click", 395, 655),
        ("click", 283, 574),
        ("delay_long",),
        ("drag", 415, 543, 342, 543), ("delay", 0.5),
        ("click", 387, 543), ("delay", 3),
        ("drag", 415, 543, 342, 543), ("delay", 0.5),
        ("click", 387, 543), ("delay", 2),
        ("click_with_delay", 414, 571, 0.4),
        ("click", 414, 571),
        ("click_with_delay", 27, 593, 0.2),
        ("click", 27, 593),
        ("delay_long",),
        ("drag", 95, 712, 18, 712), ("delay", 0.3),
        ("drag", 55, 720, 70, 720), ("delay", 0.3),
        ("drag", 95, 712, 18, 712), ("delay", 0.3),
        ("pause_click",),
    ],
    4: [
        ("pause_click",),
        ("drag", 49, 975, 107, 975),
        ("click_with_delay", 419, 793, 0.4),
        ("click", 419, 793),
        ("click", 34, 817),
        ("click", 207, 838),
        ("delay_long",),
        ("click", 241, 801),
        ("delay_long",),
        ("click", 400, 904),
        ("click", 291, 825),
        ("delay_long",),
        ("drag", 417, 793, 342, 793), ("delay", 0.5),
        ("click", 387, 793), ("delay", 3),
        ("drag", 417, 793, 342, 793), ("delay", 0.5),
        ("click", 387, 793), ("delay", 2),
        ("click_with_delay", 419, 822, 0.4),
        ("click", 419, 822),
        ("click_with_delay", 32, 844, 0.2),
        ("click", 32, 844),
        ("delay_long",),
        ("drag", 95, 963, 18, 963), ("delay", 0.3),
        ("drag", 55, 975, 70, 975), ("delay", 0.3),
        ("drag", 95, 963, 18, 963), ("delay", 0.3),
        ("pause_click",),
    ],
}
HOME_SEQUENCE = [
    ("pause_click",),
    ("drag", 49, 210, 107, 210),
    ("click", 414, 69),
    ("click", 33, 68),
    ("click", 209, 90),
    ("delay_long",),
    ("click", 240, 52),
    ("delay_long",),
    ("click", 395, 152),
    ("click", 291, 73),
    ("delay_long",),
    ("click", 35, 91),
    ("drag", 107, 210, 18, 210),
    ("pause_click",),
]

# Food: same logic as home. Use ("delay", sec) between steps.
FOOD_DRAG_DURATION = 0.4
FOOD_SEQUENCE_BY_EMULATOR = {
    1: [
        ("pause_click",), ("delay", 0.5),
        ("drag", 415, 42, 342, 42), ("delay", 0.5),
        ("click", 387, 42), ("delay", 1),
        ("drag", 415, 42, 342, 42), ("delay", 0.5),
        ("click", 387, 42), ("delay", 2),
        ("drag", 415, 42, 342, 42), ("delay", 0.5),
        ("click", 387, 42), ("delay", 2),
        ("click", 415, 67), ("delay", 0.4),
        ("click", 415, 67), ("delay", 0.4),
        ("click", 415, 67), ("delay", 0.7),
        ("click", 55, 205), ("delay", 0.7),
        ("pause_click",),
    ],
    2: [
        ("pause_click",), ("delay", 0.5),
        ("drag", 415, 295, 340, 295), ("delay", 0.5),
        ("click", 387, 295), ("delay", 1),
        ("drag", 415, 295, 340, 295), ("delay", 0.5),
        ("click", 387, 295), ("delay", 2),
        ("drag", 415, 295, 340, 295), ("delay", 0.5),
        ("click", 387, 295), ("delay", 2),
        ("click", 415, 324), ("delay", 0.4),
        ("click", 415, 324), ("delay", 0.4),
        ("click", 415, 324), ("delay", 0.7),
        ("click", 55, 465), ("delay", 0.7),
        ("pause_click",),
    ],
    3: [
        ("pause_click",), ("delay", 0.5),
        ("drag", 415, 543, 342, 543), ("delay", 0.5),
        ("click", 387, 543), ("delay", 1),
        ("drag", 415, 543, 342, 543), ("delay", 0.5),
        ("click", 387, 543), ("delay", 2),
        ("drag", 415, 543, 342, 543), ("delay", 0.5),
        ("click", 387, 543), ("delay", 2),
        ("click", 415, 567), ("delay", 0.4),
        ("click", 415, 567), ("delay", 0.4),
        ("click", 415, 567), ("delay", 0.7),
        ("click", 55, 720), ("delay", 0.7),
        ("pause_click",),
    ],
    4: [
        ("pause_click",), ("delay", 0.5),
        ("drag", 417, 793, 342, 793), ("delay", 0.5),
        ("click", 387, 793), ("delay", 1),
        ("drag", 417, 793, 342, 793), ("delay", 0.5),
        ("click", 387, 793), ("delay", 2),
        ("drag", 417, 793, 342, 793), ("delay", 0.5),
        ("click", 387, 793), ("delay", 2),
        ("click", 417, 818), ("delay", 0.4),
        ("click", 417, 818), ("delay", 0.4),
        ("click", 417, 818), ("delay", 0.7),
        ("click", 55, 960), ("delay", 0.7),
        ("pause_click",),
    ],
}

def _set_windows_dpi_aware():
    """Match cursor_detector/pyautogui coords (DPI scaling)."""
    try:
        import ctypes
        if hasattr(ctypes.windll, 'shcore'):
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        pass


class SilentMultiEmulatorAntijailMacro:
    def __init__(self):
        _set_windows_dpi_aware()
        try:
            import ctypes
            ctypes.windll.user32.ShowWindow(ctypes.windll.kernel32.GetConsoleWindow(), 0)
        except Exception:
            pass
        self.emulators = {
            1: {
                # Drill detection area (original (207,141,237,148) expanded by +2px each side)
                'detection_area': (205, 139, 239, 148),  # height -2
                'pause_button_pos': (100, 38),
                'move_start_pos': (55, 212),
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            },
            2: {
                # Original (206,398,236,405) -> (204,396,238,407)
                'detection_area': (204, 396, 238, 405),  # height -2
                'pause_button_pos': (91, 293),
                'move_start_pos': (62, 467),
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            },
            3: {
                # Original (209,647,239,654) -> (207,645,241,656)
                'detection_area': (207, 645, 241, 654),  # height -2
                'pause_button_pos': (109, 544),
                'move_start_pos': (56, 723),
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            },
            4: {
                # Original (210,895,240,902) -> (208,893,242,904)
                'detection_area': (208, 893, 242, 902),  # height -2
                'pause_button_pos': (96, 792),
                'move_start_pos': (60, 965),
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            }
        }
        self.min_drill_pixels = 5
        # Drill color detection: single color #338072 with tolerance (in RGB value, 0–255)
        # Smaller tolerance = stricter (previously 8); increase slightly if drill is often missed.
        self.color_tolerance = 15  # slightly wider to catch drill color under bright lighting
        self.drill_colors = ["#317d73", "#338072"]
        # Move sequence when drill not detected: (x1, y1, x2, y2, duration) for emulator 2; y adjusted per emu
        # Move sequence when drill not detected (for emulator 2 baseline; Y shifted per emulator).
        # Pattern: right, up-right, left, down-right, left.
        self.move_sequence_drags = [
            (45, 470, 97, 470, 0.2),    # right
            (63, 480, 105, 440, 0.3),   # up-right
            (105, 440, 45, 440, 0.3),   # left
            (45, 440, 105, 480, 0.2),   # down-right
            (105, 480, 45, 480, 0.2),   # left
            (45, 480, 97, 480, 0.15),   # right fast
        ]
        # y offset from emu2 coords: emu1 = -248, emu2 = 0, emu3 = +250, emu4 = +520
        self.move_sequence_y_offset_by_emu = {1: -248, 2: 0, 3: 250, 4: 520}
        # After no-drill drags: delay, swipe (x1,y1)->(x2,y2), delay, click (cx,cy). Screen coords per emu.
        self.post_antijail_swipe_duration = 0.35
        self.post_antijail_swipe_click_by_emu = {
            1: (412, 90, 370, 90, 321, 103),
            2: (416, 346, 367, 346, 323, 358),
            3: (417, 595, 362, 595, 324, 608),
            4: (417, 844, 359, 844, 326, 857),
        }
        self.macro_running = False
        self.detection_threads = {}
        self.keyboard_listener = None
        self.action_lock = threading.Lock()
        # When True, a no-drill antijail movement is running and should preempt home sequences
        self.antijail_running = threading.Event()
        self.sound_muted = [False]  # list so threads see current value
        self.dragging = False
        self.drag_start_x = 0
        self.drag_start_y = 0
        self.dragging_emulator = None
        self.dragging_type = None
        self.home_sequence_running = set()  # emu_ids running home (e.g. fire-triggered); skip drill antijail
        # (x1, y1, x2, y2) screen region for fire (color) detection per emulator
        self.fire_region_by_emu = {
            1: (50, 50, 400, 250),
            2: (50, 310, 400, 490),
            3: (50, 560, 395, 730),
            4: (55, 810, 395, 975),
        }
        # Fire color detection: orange fire hex colors; high similarity so dark reds (#da171e, #a10a0c, etc.) don't count
        self.fire_colors_hex = ("#ff961e", "#ff4312", "#fe941b")
        self.fire_color_similarity = 0.92  # 92% – only orange fire; excludes dark red
        self.fire_min_blob_area = 100       # blob must be at least this many pixels (small circle)
        self._fire_template = None  # loaded once (original size)
        self._fire_template_path = None
        # Similarity threshold for fire detection (0.4 = 40%)
        self.fire_similarity_threshold = 0.4
        # Allow the fire to be a bit smaller/bigger than the PNG (scale search)
        self.fire_min_scale = 0.6
        self.fire_max_scale = 1.4
        self.fire_scale_step = 0.1
        self.fire_min_template_size = 16  # ignore extremely tiny scales
        # Global layout overlay (all emu boxes) + per-emulator overlays
        self.emulator_layout_overlay = None  # big overlay window showing 4 emulator boxes
        self.emulator_layout_overlays = {1: None, 2: None, 3: None, 4: None}
        # Per-emulator box geometry and colors (screen coords)
        self.emulator_layout_boxes = {
            1: (0, 0, 495, 270),      # blue
            2: (0, 233, 495, 526),    # yellow
            3: (0, 485, 495, 774),    # green
            4: (0, 733, 495, 1021),   # purple
        }
        self.emulator_layout_colors = {
            1: "#0000ff",
            2: "#ffff00",
            3: "#00ff00",
            4: "#800080",
        }
        
        self.setup_ui()
        self.setup_keyboard_listener()
        try:
            _init_virtual_mouse()
        except Exception:
            pass

    def setup_ui(self):
        self.root = tk.Tk()
        self.root.title("AntiJail")
        self.root.geometry("150x490+470+50")
        self.root.configure(bg='#2b2b2b')
        self.virtual_input_var = tk.BooleanVar(self.root, value=True)  # after Tk() exists
        style = ttk.Style()
        try:
            style.theme_use('clam')
        except tk.TclError:
            pass
        bg_dark = '#2b2b2b'
        fg_light = '#b0b0b0'
        btn_light_blue = '#7ec8e3'
        btn_bg_gray = '#404040'
        btn_hover_dark_blue = '#1a3a6a'
        font_family = 'Comic Sans MS'
        try:
            tkfont.Font(family=font_family, size=9).actual()
        except (tk.TclError, Exception):
            font_family = 'Segoe UI'
        self.font_family = font_family
        style.configure('TFrame', background=bg_dark)
        style.configure('TLabel', background=bg_dark, foreground=fg_light, font=(font_family, 9))
        style.configure('TButton', background=btn_bg_gray, foreground=btn_light_blue,
                        padding=(4, 2), font=(font_family, 9),
                        lightcolor=btn_light_blue, darkcolor=btn_light_blue)
        style.map('TButton', background=[('active', btn_hover_dark_blue), ('pressed', btn_hover_dark_blue)])
        style.configure('TCheckbutton', background=bg_dark, foreground=fg_light, font=(font_family, 9))
        style.map('TCheckbutton', background=[('active', bg_dark)])
        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        title_label = ttk.Label(main_frame, text="MINING ANTIJAIL", font=(font_family, 8, 'bold'))
        title_label.grid(row=0, column=0, columnspan=2, sticky=tk.W, pady=(0, 2))
        for emu_id in range(1, 5):
            base_row = 1 + (emu_id - 1) * 3
            emu_label = ttk.Label(main_frame, text=f"Emulator {emu_id}", font=(font_family, 10, 'bold'))
            emu_label.grid(row=base_row, column=0, columnspan=2, sticky=tk.W, pady=(2, 0))
            status_label = ttk.Label(main_frame, text="Stopped", font=(font_family, 9, 'bold'))
            setattr(self, f'status_label_{emu_id}', status_label)
            status_label.grid(row=base_row+1, column=0, sticky=tk.W, padx=(0, 0), pady=(0, 0))
            detect_var = tk.BooleanVar()
            setattr(self, f'detect_var_{emu_id}', detect_var)
            detect_btn = ttk.Checkbutton(
                main_frame, 
                text="Detection", 
                variable=detect_var,
                command=lambda eid=emu_id: self.toggle_detection_overlay(eid)
            )
            detect_btn.grid(row=base_row+1, column=1, sticky=tk.W, padx=(2, 0), pady=(0, 0))
            btn_row = ttk.Frame(main_frame)
            btn_row.grid(row=base_row+2, column=0, sticky=tk.W, pady=(0, 5))
            individual_btn = ttk.Button(
                btn_row,
                text="▶",
                command=lambda eid=emu_id: self.toggle_individual_emulator(eid),
                width=3
            )
            setattr(self, f'individual_btn_{emu_id}', individual_btn)
            individual_btn.pack(side=tk.LEFT, padx=(0, 2))
            home_btn = ttk.Button(
                btn_row,
                text="\U0001F3E0",
                command=lambda eid=emu_id: self.run_home_sequence(eid),
                width=3
            )
            setattr(self, f'home_btn_{emu_id}', home_btn)
            home_btn.pack(side=tk.LEFT, padx=(2, 0))
            food_btn = ttk.Button(
                btn_row,
                text="\U0001F354",
                command=lambda eid=emu_id: self.run_food_sequence(eid),
                width=3
            )
            setattr(self, f'food_btn_{emu_id}', food_btn)
            food_btn.pack(side=tk.LEFT, padx=(2, 0))
            pause_var = tk.BooleanVar()
            setattr(self, f'pause_var_{emu_id}', pause_var)
            pause_btn = ttk.Checkbutton(
                main_frame, 
                text="Pause", 
                variable=pause_var,
                command=lambda eid=emu_id: self.toggle_pause_button_marker(eid)
            )
            pause_btn.grid(row=base_row+2, column=1, sticky=tk.W, padx=(2, 0), pady=(0, 5))
            # Per-emulator layout overlay toggle (opens/closes the global colored-box overlay)
            overlay_btn = ttk.Button(
                main_frame,
                text="▤",
                command=lambda eid=emu_id: self.toggle_single_emulator_layout_overlay(eid),
                width=2,
            )
            overlay_btn.grid(row=base_row+1, column=2, sticky=tk.W, padx=(2, 0), pady=(0, 0))
        self.mute_btn = ttk.Button(
            main_frame,
            text="🔊",
            command=self.toggle_sound_mute,
            width=3
        )
        self.mute_btn.grid(row=13, column=0, sticky=tk.W, padx=(0, 0), pady=(0, 2))
        self.layout_overlay_btn = ttk.Button(
            main_frame,
            text="▤",
            command=self.toggle_emulator_layout_overlay,
            width=3
        )
        self.layout_overlay_btn.grid(row=13, column=1, sticky=tk.W, padx=(2, 0), pady=(0, 2))
        virtual_cb = ttk.Checkbutton(
            main_frame,
            text="Virtual mouse (don't move cursor)",
            variable=self.virtual_input_var,
        )
        virtual_cb.grid(row=13, column=2, sticky=tk.W, padx=(2, 0), pady=(0, 2))
        control_frame = ttk.Frame(main_frame)
        control_frame.grid(row=14, column=0, columnspan=2, sticky=tk.W, pady=5)
        self.toggle_btn = ttk.Button(
            control_frame, 
            text="▶ Start All", 
            command=self.toggle_macro,
            width=10
        )
        self.toggle_btn.grid(row=0, column=0, sticky=tk.W, padx=(8, 0), pady=(2, 6))
        pause_all_btn = ttk.Button(
            control_frame, 
            text="Pause All", 
            command=self.pause_all_emulators,
            width=10
        )
        pause_all_btn.grid(row=1, column=0, sticky=tk.W, padx=(8, 0), pady=2)
        debug_btn = ttk.Button(
            control_frame, 
            text="Debug", 
            command=self.open_debug_mode,
            width=10
        )
        debug_btn.grid(row=2, column=0, sticky=tk.W, padx=(8, 0), pady=2)
        self.main_status_label = ttk.Label(main_frame, text="Status: Stopped")
        self.main_status_label.grid(row=17, column=0, columnspan=2, pady=2)
        instructions = ttk.Label(
            main_frame,
            text="NumLock: Toggle all emulators on/off\nDetection every 5 seconds per emulator\n= key: Get cursor hex color\nDrag overlays to reposition\n▶/⏸ buttons: Individual emulator control\n🔊 Sound pattern (-- pause -- pause --) when no drill\nVirtual mouse: clicks/drags in emulator without moving your cursor\nfire.png (70%+) in each emu region triggers home (Emu1-4)",
            font=(font_family, 8)
        )
        instructions.grid(row=18, column=0, columnspan=2, pady=2)
        
    def open_debug_mode(self):
        """Launch antijail_debugger.py."""
        try:
            subprocess.Popen([sys.executable, "antijail_debugger.py"])
        except Exception:
            pass
    
    def setup_keyboard_listener(self):
        """NumLock = toggle all, = key = cursor position."""
        def on_key_press(key):
            try:
                if key == keyboard.Key.num_lock:
                    if self.macro_running:
                        self.stop_macro()
                    else:
                        self.start_macro()
                elif hasattr(key, 'char') and key.char == '=':
                    self.detect_cursor_info()
            except Exception:
                pass
        
        self.keyboard_listener = keyboard.Listener(on_press=on_key_press)
        self.keyboard_listener.start()
        
    def detect_cursor_info(self):
        """Show cursor (x,y) and hex color in status (triggered by = key)."""
        try:
            from pynput.mouse import Controller
            mouse_controller = Controller()
            position = mouse_controller.position
            x, y = position
            screenshot = ImageGrab.grab()
            rgb = screenshot.getpixel((x, y))[:3]
            hex_color = "#{:02x}{:02x}{:02x}".format(rgb[0], rgb[1], rgb[2])
            self.main_status_label.config(text=f"Cursor: ({x}, {y}) {hex_color}")
            self.root.after(3000, lambda: self.main_status_label.config(text="Status: Running" if self.macro_running else "Status: Stopped"))
            
        except Exception:
            pass
            
    def toggle_detection_overlay(self, emu_id):
        """Toggle detection area overlay for this emulator."""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        detect_var = getattr(self, f'detect_var_{emu_id}')
        if detect_var.get():
            self.show_detection_overlay(emu_id)
        else:
            self.hide_detection_overlay(emu_id)
            
    def show_detection_overlay(self, emu_id):
        """Show detection area overlay (draggable)."""
        emulator = self.emulators.get(emu_id)
        if not emulator or emulator['detection_overlay']:
            return
            
        self.emulators[emu_id]['detection_overlay'] = tk.Toplevel()
        self.emulators[emu_id]['detection_overlay'].attributes('-alpha', 0.3)
        self.emulators[emu_id]['detection_overlay'].attributes('-topmost', True)
        self.emulators[emu_id]['detection_overlay'].overrideredirect(True)
        
        x1, y1, x2, y2 = emulator['detection_area']
        width = x2 - x1
        height = y2 - y1
        
        self.emulators[emu_id]['detection_overlay'].geometry(f"{width}x{height}+{x1}+{y1}")
        self.emulators[emu_id]['detection_overlay'].configure(bg='red')
        
        self.emulators[emu_id]['detection_overlay'].bind('<Button-1>', self.start_drag)
        self.emulators[emu_id]['detection_overlay'].bind('<B1-Motion>', self.on_drag)
        self.emulators[emu_id]['detection_overlay'].bind('<ButtonRelease-1>', self.end_drag)
        
    def hide_detection_overlay(self, emu_id):
        """Hide detection overlay."""
        emulator = self.emulators.get(emu_id)
        if not emulator or not emulator['detection_overlay']:
            return
            
        self.emulators[emu_id]['detection_overlay'].destroy()
        self.emulators[emu_id]['detection_overlay'] = None
        
    def start_drag(self, event):
        """Begin drag of detection overlay."""
        self.dragging = True
        for emu_id in range(1, 5):
            emulator = self.emulators[emu_id]
            if emulator['detection_overlay'] and event.widget == emulator['detection_overlay']:
                self.dragging_emulator = emu_id
                self.dragging_type = 'detection'
                break
        
        if self.dragging_emulator:
            overlay = self.emulators[self.dragging_emulator]['detection_overlay']
            self.drag_start_x = event.x_root - overlay.winfo_x()
            self.drag_start_y = event.y_root - overlay.winfo_y()
        
    def on_drag(self, event):
        """Move detection overlay while dragging."""
        if not self.dragging or not self.dragging_emulator:
            return
            
        overlay = self.emulators[self.dragging_emulator]['detection_overlay']
        if not overlay:
            return
            
        new_x = event.x_root - self.drag_start_x
        new_y = event.y_root - self.drag_start_y
        overlay.geometry(f"+{new_x}+{new_y}")
        
    def end_drag(self, event):
        """Save new detection area after drag."""
        if not self.dragging or not self.dragging_emulator:
            return
        self.dragging = False
        overlay = self.emulators[self.dragging_emulator]['detection_overlay']
        if not overlay:
            return
        x = overlay.winfo_x()
        y = overlay.winfo_y()
        width = overlay.winfo_width()
        height = overlay.winfo_height()
        self.emulators[self.dragging_emulator]['detection_area'] = (x, y, x + width, y + height)
        self.main_status_label.config(text=f"Emu {self.dragging_emulator} detection moved")
        self.root.after(2000, lambda: self.main_status_label.config(text="Status: Running" if self.macro_running else "Status: Stopped"))
        
        self.dragging_emulator = None
        self.dragging_type = None
        
    def toggle_pause_button_marker(self, emu_id):
        """Toggle pause button marker overlay."""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        pause_var = getattr(self, f'pause_var_{emu_id}')
        if pause_var.get():
            self.show_pause_button_marker(emu_id)
        else:
            self.hide_pause_button_marker(emu_id)
            
    def show_pause_button_marker(self, emu_id):
        """Show draggable pause marker."""
        emulator = self.emulators.get(emu_id)
        if not emulator or emulator['pause_button_overlay']:
            return
            
        self.emulators[emu_id]['pause_button_overlay'] = tk.Toplevel()
        self.emulators[emu_id]['pause_button_overlay'].attributes('-alpha', 0.8)
        self.emulators[emu_id]['pause_button_overlay'].attributes('-topmost', True)
        self.emulators[emu_id]['pause_button_overlay'].overrideredirect(True)
        
        x, y = emulator['pause_button_pos']
        self.emulators[emu_id]['pause_button_overlay'].geometry(f"3x3+{x-1}+{y-1}")
        self.emulators[emu_id]['pause_button_overlay'].configure(bg='black')
        
        self.emulators[emu_id]['pause_button_overlay'].bind('<Button-1>', self.start_pause_drag)
        self.emulators[emu_id]['pause_button_overlay'].bind('<B1-Motion>', self.on_pause_drag)
        self.emulators[emu_id]['pause_button_overlay'].bind('<ButtonRelease-1>', self.end_pause_drag)
        
    def hide_pause_button_marker(self, emu_id):
        """Hide pause marker."""
        emulator = self.emulators.get(emu_id)
        if not emulator or not emulator['pause_button_overlay']:
            return
            
        self.emulators[emu_id]['pause_button_overlay'].destroy()
        self.emulators[emu_id]['pause_button_overlay'] = None
        
    def start_pause_drag(self, event):
        """Begin drag of pause marker."""
        self.dragging = True
        for emu_id in range(1, 5):
            emulator = self.emulators[emu_id]
            if emulator['pause_button_overlay'] and event.widget == emulator['pause_button_overlay']:
                self.dragging_emulator = emu_id
                self.dragging_type = 'pause'
                break
        
        if self.dragging_emulator:
            overlay = self.emulators[self.dragging_emulator]['pause_button_overlay']
            self.drag_start_x = event.x_root - overlay.winfo_x()
            self.drag_start_y = event.y_root - overlay.winfo_y()
        
    def on_pause_drag(self, event):
        """Move pause marker while dragging."""
        if not self.dragging or not self.dragging_emulator:
            return
            
        overlay = self.emulators[self.dragging_emulator]['pause_button_overlay']
        if not overlay:
            return
            
        new_x = event.x_root - self.drag_start_x
        new_y = event.y_root - self.drag_start_y
        overlay.geometry(f"+{new_x}+{new_y}")
        
    def end_pause_drag(self, event):
        """Save new pause position after drag."""
        if not self.dragging or not self.dragging_emulator:
            return
        self.dragging = False
        overlay = self.emulators[self.dragging_emulator]['pause_button_overlay']
        if not overlay:
            return
        x = overlay.winfo_x()
        y = overlay.winfo_y()
        self.emulators[self.dragging_emulator]['pause_button_pos'] = (x, y)
        self.main_status_label.config(text=f"Emu {self.dragging_emulator} pause moved")
        self.root.after(2000, lambda: self.main_status_label.config(text="Status: Running" if self.macro_running else "Status: Stopped"))
        self.dragging_emulator = None
        self.dragging_type = None

    def toggle_single_emulator_layout_overlay(self, emu_id: int):
        """Toggle a transparent colored overlay for a single emulator box."""
        # Hide if already visible
        existing = self.emulator_layout_overlays.get(emu_id)
        if existing and existing.winfo_exists():
            existing.destroy()
            self.emulator_layout_overlays[emu_id] = None
            return

        box = self.emulator_layout_boxes.get(emu_id)
        color = self.emulator_layout_colors.get(emu_id, "#ffffff")
        if not box:
            return

        x1, y1, x2, y2 = box
        width = x2 - x1
        height = y2 - y1

        overlay = tk.Toplevel(self.root)
        self.emulator_layout_overlays[emu_id] = overlay
        try:
            overlay.attributes('-alpha', 0.15)  # fairly transparent
        except Exception:
            pass
        try:
            overlay.attributes('-topmost', True)
        except Exception:
            pass
        try:
            overlay.overrideredirect(True)
        except Exception:
            pass

        overlay.geometry(f"{width}x{height}+{x1}+{y1}")

        canvas = tk.Canvas(overlay, width=width, height=height, highlightthickness=0)
        canvas.pack(fill="both", expand=True)
        canvas.create_rectangle(
            0, 0, width, height,
            outline=color,
            fill=color,
        )

        def on_close():
            if self.emulator_layout_overlays.get(emu_id) is overlay:
                self.emulator_layout_overlays[emu_id] = None
            overlay.destroy()

        overlay.protocol("WM_DELETE_WINDOW", on_close)

    def toggle_emulator_layout_overlay(self):
        """Toggle big transparent overlay showing each emulator's target box."""
        # If already visible, hide it
        if self.emulator_layout_overlay and self.emulator_layout_overlay.winfo_exists():
            self.emulator_layout_overlay.destroy()
            self.emulator_layout_overlay = None
            return

        # Create overlay window spanning the 4 emulator regions
        overlay = tk.Toplevel()
        self.emulator_layout_overlay = overlay
        try:
            # Slightly more transparent so colors are softer
            overlay.attributes('-alpha', 0.15)
        except Exception:
            pass
        try:
            overlay.attributes('-topmost', True)
        except Exception:
            pass
        try:
            overlay.overrideredirect(True)
        except Exception:
            pass

        # Geometry: full area covering all 4 emulator boxes
        width = 495
        height = 1021
        overlay.geometry(f"{width}x{height}+0+0")

        canvas = tk.Canvas(overlay, width=width, height=height, highlightthickness=0)
        canvas.pack(fill="both", expand=True)

        # Emulator 1: blue - 0,0 to 495,270
        canvas.create_rectangle(
            0, 0, 495, 270,
            outline="#0000ff",
            fill="#0000ff"
        )
        # Emulator 2: yellow - 0,233 to 495,526
        canvas.create_rectangle(
            0, 233, 495, 526,
            outline="#ffff00",
            fill="#ffff00"
        )
        # Emulator 3: green - 0,485 to 495,774
        canvas.create_rectangle(
            0, 485, 495, 774,
            outline="#00ff00",
            fill="#00ff00"
        )
        # Emulator 4: purple - 0,733 to 495,1021
        canvas.create_rectangle(
            0, 733, 495, 1021,
            outline="#800080",
            fill="#800080"
        )

        # If user closes overlay manually, keep state in sync
        def on_close():
            if self.emulator_layout_overlay and self.emulator_layout_overlay.winfo_exists():
                self.emulator_layout_overlay.destroy()
            self.emulator_layout_overlay = None

        overlay.protocol("WM_DELETE_WINDOW", on_close)
        
    def detect_drill_colors(self, emu_id):
        """True if drill colors found in detection area."""
        try:
            emulator = self.emulators.get(emu_id)
            if not emulator or not emulator['active']:
                return True
                
            x1, y1, x2, y2 = emulator['detection_area']
            screenshot = ImageGrab.grab(bbox=(x1, y1, x2, y2))
            
            found_drill_colors = {}
            total_drill_pixels = 0
            
            for x in range(screenshot.width):
                for y in range(screenshot.height):
                    pixel_color = screenshot.getpixel((x, y))[:3]
                    hex_color = "#{:02x}{:02x}{:02x}".format(pixel_color[0], pixel_color[1], pixel_color[2])
                    
                    if hex_color in self.drill_colors:
                        if hex_color in found_drill_colors:
                            found_drill_colors[hex_color] += 1
                        else:
                            found_drill_colors[hex_color] = 1
                        total_drill_pixels += 1
                    else:
                        for drill_color in self.drill_colors:
                            if self.hex_color_matches(hex_color, drill_color, self.color_tolerance):
                                if hex_color in found_drill_colors:
                                    found_drill_colors[hex_color] += 1
                                else:
                                    found_drill_colors[hex_color] = 1
                                total_drill_pixels += 1
                                break
            
            if emu_id == 1:
                required_pixels = 10
            else:
                required_pixels = self.min_drill_pixels
            
            return total_drill_pixels >= required_pixels
            
        except Exception:
            return True

    def hex_color_matches(self, hex1, hex2, tolerance=10):
        """Hex colors match within tolerance."""
        try:
            rgb1 = tuple(int(hex1[i:i+2], 16) for i in (1, 3, 5))
            rgb2 = tuple(int(hex2[i:i+2], 16) for i in (1, 3, 5))
            return all(abs(c1 - c2) <= tolerance for c1, c2 in zip(rgb1, rgb2))
        except:
            return False

    def toggle_sound_mute(self):
        """Toggle alarm mute (🔊/🔇)."""
        self.sound_muted[0] = not self.sound_muted[0]
        self.mute_btn.config(text="🔇" if self.sound_muted[0] else "🔊")

    def play_alert_sound(self):
        """Play alarm (skipped if muted)."""
        if self.sound_muted[0]:
            return
        def sound_thread():
            try:
                alarm_path = os.path.join(SCRIPT_DIR, "soundalarm.mp3")
                if PYGAME_AVAILABLE and os.path.isfile(alarm_path):
                    pygame.mixer.init()
                    pygame.mixer.music.load(alarm_path)
                    pygame.mixer.music.play()
                    while pygame.mixer.music.get_busy():
                        time.sleep(0.1)
                else:
                    winsound.Beep(1000, 500)
            except Exception:
                try:
                    winsound.Beep(1000, 500)
                except:
                    pass
        
        threading.Thread(target=sound_thread, daemon=True).start()

    def _click(self, x, y):
        """Click at (x,y). Uses virtual mouse if enabled so real cursor doesn't move."""
        if _virtual_mouse_available() and self.virtual_input_var.get():
            if virtual_click(x, y):
                return
        pyautogui.click(x, y)

    def _dragFromTo(self, x1, y1, x2, y2, duration=0.2):
        """Drag from (x1,y1) to (x2,y2). Uses virtual mouse if enabled."""
        if _virtual_mouse_available() and self.virtual_input_var.get():
            if virtual_drag(x1, y1, x2, y2, duration):
                return
        pyautogui.moveTo(x1, y1)
        pyautogui.drag(x2 - x1, y2 - y1, duration=duration)
        
    def perform_antijail_action(self, emu_id):
        """Pause twice, move sequence, deactivate emulator."""
        # Mark that antijail is running so home sequences will wait
        self.antijail_running.set()
        with self.action_lock:
            try:
                emulator = self.emulators.get(emu_id)
                if not emulator:
                    return
                emulator['in_action'] = True
                self.play_alert_sound()
                status_label = getattr(self, f'status_label_{emu_id}', None)
                if status_label:
                    status_label.config(text="In Action", foreground='orange', font=(self.font_family, 9, 'bold'))
                pause_x, pause_y = emulator['pause_button_pos']
                self._click(pause_x, pause_y)
                time.sleep(0.1)
                self._click(pause_x, pause_y)
                time.sleep(1.0)
                y_off = self.move_sequence_y_offset_by_emu.get(emu_id, 0)
                for x1, y1, x2, y2, duration in self.move_sequence_drags:
                    self._dragFromTo(x1, y1 + y_off, x2, y2 + y_off, duration=duration)
                    time.sleep(0.1)
                post = self.post_antijail_swipe_click_by_emu.get(emu_id)
                if post:
                    sx1, sy1, sx2, sy2, tcx, tcy = post
                    time.sleep(0.5)
                    self._dragFromTo(sx1, sy1, sx2, sy2, duration=self.post_antijail_swipe_duration)
                    time.sleep(0.5)
                    self._click(tcx, tcy)
                emulator['active'] = False
                emulator['in_action'] = False
                if status_label:
                    status_label.config(text="Paused", foreground='#f08080', font=(self.font_family, 9, 'bold'))
                
            except Exception:
                if emulator:
                    emulator['in_action'] = False
            finally:
                # Allow home sequences to continue
                self.antijail_running.clear()

    def _get_fire_template(self):
        """Load fire.png once; return (template as numpy BGR, path) or (None, None)."""
        if not FIRE_DETECTION_AVAILABLE:
            return None, None
        for name in ('fire.png', os.path.join('assets', 'fire.png')):
            path = os.path.join(SCRIPT_DIR, name)
            if os.path.isfile(path):
                try:
                    img = cv2.imread(path)
                    if img is not None:
                        return img, path
                except Exception:
                    pass
        return None, None

    def detect_fire(self, emu_id):
        """True if fire.png is detected in this emulator's region with >= 70% similarity (multi-scale)."""
        if not FIRE_DETECTION_AVAILABLE:
            return False
        region = self.fire_region_by_emu.get(emu_id)
        if not region:
            return False
        try:
            template, path = self._get_fire_template()
            if template is None:
                return False
            # Cache the original template (full size)
            if self._fire_template is None or self._fire_template_path != path:
                self._fire_template = template
                self._fire_template_path = path
            x1, y1, x2, y2 = region
            screenshot = ImageGrab.grab(bbox=(x1, y1, x2, y2))
            frame = np.array(screenshot)
            frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)
            fh, fw = frame.shape[:2]
            th_orig, tw_orig = self._fire_template.shape[:2]
            best_val = 0.0
            # Multi-scale template matching so fire can be a bit smaller/bigger than the PNG
            scale = self.fire_min_scale
            while scale <= self.fire_max_scale + 1e-6:
                tw = int(tw_orig * scale)
                th = int(th_orig * scale)
                if tw < self.fire_min_template_size or th < self.fire_min_template_size:
                    scale += self.fire_scale_step
                    continue
                if th > fh or tw > fw:
                    scale += self.fire_scale_step
                    continue
                tmpl = cv2.resize(self._fire_template, (tw, th), interpolation=cv2.INTER_AREA)
                result = cv2.matchTemplate(frame, tmpl, cv2.TM_CCOEFF_NORMED)
                _, max_val, _, _ = cv2.minMaxLoc(result)
                if max_val > best_val:
                    best_val = max_val
                if best_val >= self.fire_similarity_threshold:
                    break
                scale += self.fire_scale_step
            return bool(best_val >= self.fire_similarity_threshold)
        except Exception:
            return False

    def detect_fire_color(self, emu_id):
        """True if a blob of fire colors (#ff961e, #ff4312, #fe941b) is found in this emu's region (80% similarity, min blob size)."""
        if not FIRE_DETECTION_AVAILABLE:
            return False
        region = self.fire_region_by_emu.get(emu_id)
        if not region:
            return False
        try:
            x1, y1, x2, y2 = region
            screenshot = ImageGrab.grab(bbox=(x1, y1, x2, y2))
            frame = np.array(screenshot)  # RGB
            h, w = frame.shape[:2]
            # RGB of the 3 fire colors
            fire_rgb = np.array([
                (0xFF, 0x96, 0x1E),  # #ff961e
                (0xFF, 0x43, 0x12),  # #ff4312
                (0xFE, 0x94, 0x1B),  # #fe941b
            ], dtype=np.float32)
            # Max distance in RGB for 80% similarity: 0.2 * 255 * sqrt(3) ≈ 88
            max_dist = (1.0 - self.fire_color_similarity) * 255.0 * np.sqrt(3.0)
            # For each pixel, min distance to any fire color
            frame_f = frame.astype(np.float32)
            diff = frame_f[:, :, np.newaxis, :] - fire_rgb[np.newaxis, np.newaxis, :, :]  # (H,W,3,3)
            dist = np.sqrt(np.sum(diff ** 2, axis=-1))   # (H,W,3)
            min_dist = np.min(dist, axis=-1)             # (H,W)
            mask = (min_dist <= max_dist).astype(np.uint8)
            # Connected components (blobs)
            num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
            if num_labels < 2:
                return False
            areas = stats[1:, cv2.CC_STAT_AREA]
            return np.any(areas >= self.fire_min_blob_area)
        except Exception:
            return False

    def detect_screen_shake(self, emu_id):
        """True if this emulator's region shows sustained shaking for shake_duration_sec (e.g. 2–3 s)."""
        if not FIRE_DETECTION_AVAILABLE:
            return False
        region = self.fire_region_by_emu.get(emu_id)
        if not region:
            return False
        try:
            x1, y1, x2, y2 = region
            duration = max(0.5, self.shake_duration_sec)
            interval = max(0.05, self.shake_sample_interval)
            num_checks = max(1, int(duration / interval))
            shake_count = 0
            for _ in range(num_checks):
                img1 = ImageGrab.grab(bbox=(x1, y1, x2, y2))
                time.sleep(self.shake_delay)
                img2 = ImageGrab.grab(bbox=(x1, y1, x2, y2))
                a1 = np.array(img1, dtype=np.float32)
                a2 = np.array(img2, dtype=np.float32)
                mean_diff = float(np.mean(np.abs(a1 - a2)))
                if mean_diff >= self.shake_threshold:
                    shake_count += 1
                time.sleep(max(0, interval - self.shake_delay))
            return (shake_count / num_checks) >= self.shake_min_ratio
        except Exception:
            return False

    def detection_loop(self, emu_id):
        """Poll drill; run antijail if none. Check fire (color blob) in this emu's region -> home sequence."""
        while self.macro_running:
            try:
                emulator = self.emulators.get(emu_id)
                if not emulator or not emulator['active']:
                    break
                if FIRE_AUTO_HOME_ENABLED and emu_id not in self.home_sequence_running:
                    if self.detect_fire_color(emu_id):
                        print(f"[Fire] Emulator {emu_id}: fire blob detected, starting home sequence")
                        self.home_sequence_running.add(emu_id)
                        self.run_home_sequence(emu_id)
                        time.sleep(2)
                        continue
                if not self.detect_drill_colors(emu_id):
                    if emu_id not in self.home_sequence_running:
                        self.perform_antijail_action(emu_id)
                time.sleep(2)
            except Exception:
                time.sleep(1.0)

    def start_macro(self):
        """Start all emulators."""
        if self.macro_running:
            return
            
        for emu_id in range(1, 5):
            self.emulators[emu_id]['active'] = True
            self.emulators[emu_id]['in_action'] = False
            status_label = getattr(self, f'status_label_{emu_id}', None)
            individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
            if status_label:
                status_label.config(text="Active", foreground='#7fdb7f', font=(self.font_family, 9, 'bold'))
            if individual_btn:
                individual_btn.config(text="⏸")
            
        self.macro_running = True
        self.main_status_label.config(text="Status: Running All Emulators")
        self.toggle_btn.config(text="⏸ Stop All")
        # Click each emulator with actual mouse cursor (only these 4; rest of app uses virtual mouse if enabled)
        start_all_click_positions = [(464, 863), (465, 595), (463, 353), (459, 103)]  # emu 1–4
        for (cx, cy) in start_all_click_positions:
            try:
                pyautogui.click(cx, cy)
                time.sleep(0.15)
            except Exception:
                pass
        for emu_id in range(1, 5):
            thread = threading.Thread(target=self.detection_loop, args=(emu_id,), daemon=True)
            self.detection_threads[emu_id] = thread
            thread.start()
        
    def stop_macro(self):
        """Stop all emulators."""
        self.macro_running = False
        
        for emu_id in range(1, 5):
            self.emulators[emu_id]['active'] = False
            self.emulators[emu_id]['in_action'] = False
            status_label = getattr(self, f'status_label_{emu_id}', None)
            individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
            if status_label:
                status_label.config(text="Stopped", foreground='#9e9e9e', font=(self.font_family, 9, 'bold'))
            if individual_btn:
                individual_btn.config(text="▶")
        
        self.main_status_label.config(text="Status: Stopped")
        self.toggle_btn.config(text="▶ Start All")
        self.detection_threads.clear()
        
    def toggle_macro(self):
        """Start/stop all."""
        if self.macro_running:
            self.stop_macro()
        else:
            self.start_macro()

    def toggle_individual_emulator(self, emu_id):
        """Toggle this emulator on/off."""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        individual_btn = getattr(self, f'individual_btn_{emu_id}')
        status_label = getattr(self, f'status_label_{emu_id}')
        
        if emulator['active']:
            emulator['active'] = False
            emulator['in_action'] = False
            individual_btn.config(text="▶")
            status_label.config(text="Stopped", foreground='#9e9e9e', font=(self.font_family, 9, 'bold'))
        else:
            if not self.macro_running:
                self.macro_running = True
                self.main_status_label.config(text="Status: Individual Emulators Running")
                self.toggle_btn.config(text="⏸ Stop All")
            
            emulator['active'] = True
            emulator['in_action'] = False
            individual_btn.config(text="⏸")
            status_label.config(text="Active", foreground='#7fdb7f', font=(self.font_family, 9, 'bold'))
            
            if emu_id not in self.detection_threads or not self.detection_threads[emu_id].is_alive():
                thread = threading.Thread(target=self.detection_loop, args=(emu_id,), daemon=True)
                self.detection_threads[emu_id] = thread
                thread.start()
            
    def pause_all_emulators(self):
        """Click pause on all active emulators (with delay between)."""
        def pause_sequence():
            for emu_id in range(1, 5):
                if self.emulators[emu_id]['active']:
                    pause_x, pause_y = self.emulators[emu_id]['pause_button_pos']
                    self._click(pause_x, pause_y)
                    time.sleep(0.1)
                    self._click(pause_x, pause_y)
                    time.sleep(0.5)
                    self.main_status_label.config(text=f"Paused Emulator {emu_id}")
                    self.root.after(1000, lambda: self.main_status_label.config(
                        text="Status: Running" if self.macro_running else "Status: Stopped"
                    ))
                
        threading.Thread(target=pause_sequence, daemon=True).start()
    
    def _resume_emulator_after_home(self, emu_id, was_active):
        """Resume this emulator after home/food sequence (main thread)."""
        self.home_sequence_running.discard(emu_id)
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
        status_label = getattr(self, f'status_label_{emu_id}', None)
        individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
        if was_active:
            emulator['active'] = True
            emulator['in_action'] = False
            if status_label:
                status_label.config(text="Active", foreground='#7fdb7f', font=(self.font_family, 9, 'bold'))
            if individual_btn:
                individual_btn.config(text="⏸")
            if emu_id not in self.detection_threads or not self.detection_threads[emu_id].is_alive():
                thread = threading.Thread(target=self.detection_loop, args=(emu_id,), daemon=True)
                self.detection_threads[emu_id] = thread
                thread.start()
        else:
            if status_label:
                status_label.config(text="Stopped", foreground='#9e9e9e', font=(self.font_family, 9, 'bold'))
            if individual_btn:
                individual_btn.config(text="▶")
        self.main_status_label.config(
            text="Status: Running" if self.macro_running else "Status: Stopped"
        )

    def run_home_sequence(self, emu_id):
        """Pause this emulator, run home sequence, resume when done."""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
        emulator_was_active = emulator['active']
        emulator['active'] = False
        emulator['in_action'] = False
        status_label = getattr(self, f'status_label_{emu_id}', None)
        individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
        if status_label:
            status_label.config(text="Home...", foreground='orange', font=(self.font_family, 9, 'bold'))
        if individual_btn:
            individual_btn.config(text="▶")
        self.main_status_label.config(text=f"Home sequence running (Emu {emu_id})")
        if emu_id in HOME_SEQUENCE_BY_EMULATOR:
            steps = HOME_SEQUENCE_BY_EMULATOR[emu_id]
            ox, oy = HOME_OFFSET
        else:
            steps = HOME_SEQUENCE
            ox, oy = HOME_OFFSET
            oy += (emu_id - 1) * HOME_Y_OFFSET_PER_EMULATOR

        def home_thread():
            try:
                for step in steps:
                    # If a no-drill antijail movement is currently running, wait until it finishes
                    while self.antijail_running.is_set() and self.macro_running:
                        time.sleep(0.05)
                    kind = step[0]
                    if kind == "click":
                        x, y = int(step[1]) + ox, int(step[2]) + oy
                        with self.action_lock:
                            self._click(x, y)
                        time.sleep(HOME_DELAY)
                    elif kind == "pause_click":
                        emulator_local = self.emulators.get(emu_id)
                        if emulator_local:
                            pause_x, pause_y = emulator_local.get('pause_button_pos', (0, 0))
                            with self.action_lock:
                                self._click(int(pause_x), int(pause_y))
                        time.sleep(HOME_DELAY)
                    elif kind == "click_with_delay":
                        x, y = int(step[1]) + ox, int(step[2]) + oy
                        delay_after = float(step[3])
                        with self.action_lock:
                            self._click(x, y)
                        time.sleep(delay_after)
                    elif kind == "drag":
                        x1, y1 = int(step[1]) + ox, int(step[2]) + oy
                        x2, y2 = int(step[3]) + ox, int(step[4]) + oy
                        drag_duration = HOME_DRAG_DURATION_RIGHT_TO_LEFT if x2 < x1 else HOME_DRAG_DURATION
                        with self.action_lock:
                            self._dragFromTo(x1, y1, x2, y2, duration=drag_duration)
                        time.sleep(HOME_DELAY)
                    elif kind == "delay_long":
                        time.sleep(HOME_DELAY_LONG)
                self.root.after(0, lambda: self._resume_emulator_after_home(emu_id, emulator_was_active))
            except Exception:
                self.root.after(0, lambda: self._resume_emulator_after_home(emu_id, emulator_was_active))
        threading.Thread(target=home_thread, daemon=True).start()

    def run_food_sequence(self, emu_id):
        """Pause this emulator, run food sequence, resume when done (drill has priority)."""
        emulator = self.emulators.get(emu_id)
        if not emulator or emu_id not in FOOD_SEQUENCE_BY_EMULATOR:
            return
        emulator_was_active = emulator['active']
        emulator['active'] = False
        emulator['in_action'] = False
        status_label = getattr(self, f'status_label_{emu_id}', None)
        individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
        if status_label:
            status_label.config(text="Food...", foreground='orange', font=(self.font_family, 9, 'bold'))
        if individual_btn:
            individual_btn.config(text="▶")
        self.main_status_label.config(text=f"Food sequence running (Emu {emu_id})")

        steps = FOOD_SEQUENCE_BY_EMULATOR[emu_id]

        def food_thread():
            try:
                for step in steps:
                    kind = step[0]
                    if kind == "click":
                        x, y = int(step[1]), int(step[2])
                        with self.action_lock:
                            self._click(x, y)
                    elif kind == "pause_click":
                        pause_x, pause_y = emulator.get('pause_button_pos', (0, 0))
                        with self.action_lock:
                            self._click(int(pause_x), int(pause_y))
                    elif kind == "drag":
                        x1, y1 = int(step[1]), int(step[2])
                        x2, y2 = int(step[3]), int(step[4])
                        with self.action_lock:
                            self._dragFromTo(x1, y1, x2, y2, duration=FOOD_DRAG_DURATION)
                    elif kind == "delay":
                        time.sleep(float(step[1]))
                self.root.after(0, lambda: self._resume_emulator_after_home(emu_id, emulator_was_active))
            except Exception:
                self.root.after(0, lambda: self._resume_emulator_after_home(emu_id, emulator_was_active))
        threading.Thread(target=food_thread, daemon=True).start()
        
    def run(self):
        """Start mainloop."""
        try:
            self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
            self.root.mainloop()
        except KeyboardInterrupt:
            pass
        finally:
            self.cleanup()
            
    def on_closing(self):
        """Cleanup and destroy window."""
        self.cleanup()
        self.root.destroy()
        
    def cleanup(self):
        """Stop macro, hide overlays, stop listener."""
        self.macro_running = False
        for emu_id in range(1, 5):
            self.hide_detection_overlay(emu_id)
            self.hide_pause_button_marker(emu_id)
        if self.emulator_layout_overlay and self.emulator_layout_overlay.winfo_exists():
            self.emulator_layout_overlay.destroy()
            self.emulator_layout_overlay = None
        # Per-emulator layout overlays
        for emu_id, overlay in list(self.emulator_layout_overlays.items()):
            if overlay and overlay.winfo_exists():
                overlay.destroy()
            self.emulator_layout_overlays[emu_id] = None
        
        if self.keyboard_listener:
            self.keyboard_listener.stop()

def main():
    try:
        pyautogui.FAILSAFE = False
        pyautogui.PAUSE = 0.01
        
        macro = SilentMultiEmulatorAntijailMacro()
        macro.run()
        
    except Exception as e:
        try:
            root = tk.Tk()
            root.withdraw()
            from tkinter import messagebox
            messagebox.showerror("AntiJail Error", str(e))
            root.destroy()
        except Exception:
            pass
        import traceback
        traceback.print_exc()
        
if __name__ == "__main__":
    main() 