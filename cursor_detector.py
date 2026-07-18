#!/usr/bin/env python3
"""
Cursor Position and RGB Detector
Detects cursor x,y position and RGB values when = key is pressed
"""

import sys
from pynput import keyboard, mouse
from PIL import ImageGrab
import threading
import time


def _set_windows_dpi_aware():
    """Match screen coordinates with AntiJail/pyautogui (fixes DPI scaling / multi-monitor)."""
    try:
        import ctypes
        if hasattr(ctypes.windll, 'shcore'):
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        pass


class CursorDetector:
    def __init__(self):
        self.running = True
        print("Cursor Detector Started!")
        print("Press the '=' key to get cursor position and RGB values")
        print("Press Ctrl+C or ESC to exit")
        print("-" * 0)

    def get_cursor_position(self):
        """Get current cursor position"""
        try:
            # Get current mouse position
            position = mouse.Controller().position
            return position
        except Exception as e:
            print(f"Error getting cursor position: {e}")
            return None

    def get_rgb_at_position(self, x, y):
        """Get RGB values at specified position"""
        try:
            # Take screenshot and get pixel color
            screenshot = ImageGrab.grab()
            rgb = screenshot.getpixel((x, y))
            return rgb
        except Exception as e:
            print(f"Error getting RGB values: {e}")
            return None

    def on_key_press(self, key):
        """Handle key press events"""
        try:
            # Check if = key is pressed
            if hasattr(key, 'char') and key.char == '=':
                self.detect_cursor_info()
            elif key == keyboard.Key.esc:
                print("\nExiting...")
                self.running = False
                return False
        except AttributeError:
            # Handle special keys
            if key == keyboard.Key.esc:
                print("\nExiting...")
                self.running = False
                return False

    def detect_cursor_info(self):
        """Main function to detect cursor position and RGB values"""
        # Get cursor position
        position = self.get_cursor_position()
        if position is None:
            return

        x, y = position
        
        # Get RGB values at cursor position
        rgb = self.get_rgb_at_position(x, y)
        if rgb is None:
            return

        # Display information
        print(f"Cursor Position: X={x}, Y={y}")
        print(f"RGB Values: R={rgb[0]}, G={rgb[1]}, B={rgb[2]}")
        print(f"Hex Color: #{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}")
        print("-" * 50)

    def start_listener(self):
        """Start the keyboard listener"""
        with keyboard.Listener(on_press=self.on_key_press) as listener:
            while self.running:
                time.sleep(0.1)
            listener.stop()

def main():
    _set_windows_dpi_aware()
    try:
        detector = CursorDetector()
        detector.start_listener()
    except KeyboardInterrupt:
        print("\nProgram interrupted by user")
    except Exception as e:
        print(f"An error occurred: {e}")
    finally:
        print("Goodbye!")

if __name__ == "__main__":
    main() 