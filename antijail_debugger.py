#!/usr/bin/env python3
"""
Multi-Emulator Antijail Macro
Detects drill colors and performs antijail actions for 4 emulators simultaneously
"""

import tkinter as tk
from tkinter import ttk
import threading
import time
import pyautogui
from PIL import Image, ImageTk, ImageGrab
from pynput import keyboard
import sys
import winsound

class MultiEmulatorAntijailMacro:
    def __init__(self):
        # Emulator configurations (4 emulators with 250px Y offset each)
        self.emulators = {
            1: {
                'detection_area': (207, 141, 237, 148),
                'pause_button_pos': (99, 49),
                'move_start_pos': (65, 175),
                'active': True,  # Whether this emulator is still detecting
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,  # Whether currently performing antijail action
            },
            2: {
                'detection_area': (206, 398, 236, 405), 
                'pause_button_pos': (94, 298), 
                'move_start_pos': (65, 425), 
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            },
            3: {
                'detection_area': (209, 647, 239, 654), 
                'pause_button_pos': (105, 545), 
                'move_start_pos': (65, 675),  
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            },
            4: {
                'detection_area': (210, 895, 240, 902),  
                'pause_button_pos': (94, 789),  
                'move_start_pos': (65, 925),  
                'active': True,
                'detection_overlay': None,
                'pause_button_overlay': None,
                'in_action': False,
            }
        }
        
        # Detection settings
        self.min_drill_pixels = 5
        self.color_tolerance = 5
        
        # Drill colors to detect
        self.drill_colors = [
            "#474747",
            "#211c1a",
            "#211a19",
            "#231f1e",
            "#363636",
            "#333333",
            "#2a2a2a",
            "#252525",
        ]
        
        # Movement sequence: left, left, up, down, right
        self.move_sequence_offsets = [
            (-25, 0),   # left
            (-25, 0),   # left again
            (0, -10),   # up
            (0, 20),    # down
            (20, 0),    # right
        ]
        
        # State variables
        self.macro_running = False
        self.detection_threads = {}
        self.keyboard_listener = None
        self.action_lock = threading.Lock()  # Lock for sequential actions
        
        # Dragging state
        self.dragging = False
        self.drag_start_x = 0
        self.drag_start_y = 0
        self.dragging_emulator = None
        self.dragging_type = None  # 'detection' or 'pause'
        
        self.setup_ui()
        self.setup_keyboard_listener()
        
    def setup_ui(self):
        """Setup the main UI window"""
        self.root = tk.Tk()
        self.root.title("Multi-Emulator Antijail Macro")
        self.root.geometry("400x400")
        self.root.attributes('-topmost', True)
        
        # Main frame
        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        
        # Title
        title_label = ttk.Label(main_frame, text="4-Emulator Antijail Macro", font=('Arial', 12, 'bold'))
        title_label.grid(row=0, column=0, columnspan=4, pady=5)
        
        # Emulator controls
        for emu_id in range(1, 5):
            col = (emu_id - 1) % 2
            base_row = 1 + ((emu_id - 1) // 2) * 5  # Increased spacing for new button
            
            # Emulator label
            emu_label = ttk.Label(main_frame, text=f"Emulator {emu_id}", font=('Arial', 10, 'bold'))
            emu_label.grid(row=base_row, column=col*2, columnspan=2, pady=2, sticky=tk.W)
            
            # Detection area toggle
            detect_var = tk.BooleanVar()
            setattr(self, f'detect_var_{emu_id}', detect_var)
            detect_btn = ttk.Checkbutton(
                main_frame, 
                text="Detection", 
                variable=detect_var,
                command=lambda eid=emu_id: self.toggle_detection_overlay(eid)
            )
            detect_btn.grid(row=base_row+1, column=col*2, sticky=tk.W)
            
            # Pause button marker toggle
            pause_var = tk.BooleanVar()
            setattr(self, f'pause_var_{emu_id}', pause_var)
            pause_btn = ttk.Checkbutton(
                main_frame, 
                text="Pause", 
                variable=pause_var,
                command=lambda eid=emu_id: self.toggle_pause_button_marker(eid)
            )
            pause_btn.grid(row=base_row+1, column=col*2+1, sticky=tk.W)
            
            # Individual start/stop button
            individual_btn = ttk.Button(
                main_frame,
                text="▶",
                command=lambda eid=emu_id: self.toggle_individual_emulator(eid),
                width=3
            )
            setattr(self, f'individual_btn_{emu_id}', individual_btn)
            individual_btn.grid(row=base_row+2, column=col*2, sticky=tk.W)
            
            # Status label
            status_label = ttk.Label(main_frame, text="Stopped", foreground='gray')
            setattr(self, f'status_label_{emu_id}', status_label)
            status_label.grid(row=base_row+2, column=col*2+1, sticky=tk.W)
        
        # Main control buttons
        control_frame = ttk.Frame(main_frame)
        control_frame.grid(row=11, column=0, columnspan=4, pady=10)
        
        # Toggle button (Start/Stop)
        self.toggle_btn = ttk.Button(
            control_frame, 
            text="▶ Start All", 
            command=self.toggle_macro,
            width=15
        )
        self.toggle_btn.grid(row=0, column=0, padx=5)
        
        # Test button
        test_btn = ttk.Button(
            control_frame, 
            text="Test All Emulators", 
            command=self.test_all_emulators,
            width=15
        )
        test_btn.grid(row=0, column=1, padx=5)
        
        # Main status label
        self.main_status_label = ttk.Label(main_frame, text="Status: Stopped")
        self.main_status_label.grid(row=12, column=0, columnspan=4, pady=5)
        
        # Instructions
        instructions = ttk.Label(
            main_frame, 
            text="NumLock: Toggle all emulators on/off\nDetection every 5 seconds per emulator\n= key: Get cursor hex color\nDrag overlays to reposition\n▶/⏸ buttons: Individual emulator control\n🔊 Sound pattern (-- pause -- pause --) when no drill",
            font=('Arial', 8),
            foreground='gray'
        )
        instructions.grid(row=13, column=0, columnspan=4, pady=5)
        
    def setup_keyboard_listener(self):
        """Setup keyboard listener for NumLock toggle and = key for cursor detection"""
        def on_key_press(key):
            try:
                if key == keyboard.Key.num_lock:
                    if self.macro_running:
                        self.stop_macro()
                    else:
                        self.start_macro()
                elif hasattr(key, 'char') and key.char == '=':
                    self.detect_cursor_info()
            except Exception as e:
                print(f"Keyboard listener error: {e}")
        
        self.keyboard_listener = keyboard.Listener(on_press=on_key_press)
        self.keyboard_listener.start()
        
    def detect_cursor_info(self):
        """Get cursor position and RGB/hex values when = key is pressed"""
        try:
            # Get current cursor position
            from pynput.mouse import Controller
            mouse_controller = Controller()
            position = mouse_controller.position
            x, y = position
            
            # Get RGB values at cursor position
            screenshot = ImageGrab.grab()
            rgb = screenshot.getpixel((x, y))[:3]  # RGB only
            
            # Convert to hex
            hex_color = "#{:02x}{:02x}{:02x}".format(rgb[0], rgb[1], rgb[2])
            
            # Display information
            print("=" * 50)
            print(f"🎯 CURSOR DETECTION:")
            print(f"📍 Position: X={x}, Y={y}")
            print(f"🎨 RGB Values: R={rgb[0]}, G={rgb[1]}, B={rgb[2]}")
            print(f"🎨 Hex Color: {hex_color}")
            print(f"🔍 Is this a drill color? {'✅ YES' if hex_color in self.drill_colors else '❌ NO'}")
            print("=" * 50)
            
        except Exception as e:
            print(f"Cursor detection error: {e}")
            
    def toggle_detection_overlay(self, emu_id):
        """Toggle the detection area overlay for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        detect_var = getattr(self, f'detect_var_{emu_id}')
        if detect_var.get():
            self.show_detection_overlay(emu_id)
        else:
            self.hide_detection_overlay(emu_id)
            
    def show_detection_overlay(self, emu_id):
        """Show transparent detection area overlay for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        if emulator['detection_overlay']:
            return
            
        self.emulators[emu_id]['detection_overlay'] = tk.Toplevel()
        self.emulators[emu_id]['detection_overlay'].attributes('-alpha', 0.3)  # Transparent
        self.emulators[emu_id]['detection_overlay'].attributes('-topmost', True)
        self.emulators[emu_id]['detection_overlay'].overrideredirect(True)  # Remove window decorations
        
        # Position and size the overlay
        x1, y1, x2, y2 = emulator['detection_area']
        width = x2 - x1
        height = y2 - y1
        
        self.emulators[emu_id]['detection_overlay'].geometry(f"{width}x{height}+{x1}+{y1}")
        self.emulators[emu_id]['detection_overlay'].configure(bg='red')
        
        # Bind drag events directly to the overlay window
        self.emulators[emu_id]['detection_overlay'].bind('<Button-1>', self.start_drag)
        self.emulators[emu_id]['detection_overlay'].bind('<B1-Motion>', self.on_drag)
        self.emulators[emu_id]['detection_overlay'].bind('<ButtonRelease-1>', self.end_drag)
        
    def hide_detection_overlay(self, emu_id):
        """Hide detection area overlay for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        if emulator['detection_overlay']:
            self.emulators[emu_id]['detection_overlay'].destroy()
            self.emulators[emu_id]['detection_overlay'] = None
            
    def start_drag(self, event):
        """Start dragging the detection area"""
        self.dragging = True
        # Find which emulator's overlay is being dragged
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
        """Handle dragging the detection area"""
        if not self.dragging or not self.dragging_emulator:
            return
            
        overlay = self.emulators[self.dragging_emulator]['detection_overlay']
        if not overlay:
            return
            
        # Calculate new window position
        new_x = event.x_root - self.drag_start_x
        new_y = event.y_root - self.drag_start_y
        
        # Move the overlay to new position
        overlay.geometry(f"+{new_x}+{new_y}")
        
    def end_drag(self, event):
        """End dragging and update detection area coordinates"""
        if not self.dragging or not self.dragging_emulator:
            return
            
        self.dragging = False
        
        overlay = self.emulators[self.dragging_emulator]['detection_overlay']
        if not overlay:
            return
            
        # Get final position and update detection area coordinates
        x = overlay.winfo_x()
        y = overlay.winfo_y()
        width = overlay.winfo_width()
        height = overlay.winfo_height()
        
        # Update detection area
        self.emulators[self.dragging_emulator]['detection_area'] = (x, y, x + width, y + height)
        
        print(f"🎯 Emu {self.dragging_emulator} - Detection area moved to: ({x}, {y}) to ({x + width}, {y + height})")
        
        self.dragging_emulator = None
        self.dragging_type = None
            
    def toggle_pause_button_marker(self, emu_id):
        """Toggle the pause button marker overlay for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        pause_var = getattr(self, f'pause_var_{emu_id}')
        if pause_var.get():
            self.show_pause_button_marker(emu_id)
        else:
            self.hide_pause_button_marker(emu_id)
            
    def show_pause_button_marker(self, emu_id):
        """Show 3x3px pause button marker for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        if emulator['pause_button_overlay']:
            return
            
        self.emulators[emu_id]['pause_button_overlay'] = tk.Toplevel()
        self.emulators[emu_id]['pause_button_overlay'].attributes('-alpha', 0.8)  # Semi-transparent
        self.emulators[emu_id]['pause_button_overlay'].attributes('-topmost', True)
        self.emulators[emu_id]['pause_button_overlay'].overrideredirect(True)  # Remove window decorations
        
        # Position and size the overlay (3x3px centered on pause button)
        x, y = emulator['pause_button_pos']
        self.emulators[emu_id]['pause_button_overlay'].geometry(f"3x3+{x-1}+{y-1}")
        self.emulators[emu_id]['pause_button_overlay'].configure(bg='black')
        
        # Bind drag events to the pause button overlay
        self.emulators[emu_id]['pause_button_overlay'].bind('<Button-1>', self.start_pause_drag)
        self.emulators[emu_id]['pause_button_overlay'].bind('<B1-Motion>', self.on_pause_drag)
        self.emulators[emu_id]['pause_button_overlay'].bind('<ButtonRelease-1>', self.end_pause_drag)
        
    def hide_pause_button_marker(self, emu_id):
        """Hide pause button marker for a specific emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        if emulator['pause_button_overlay']:
            self.emulators[emu_id]['pause_button_overlay'].destroy()
            self.emulators[emu_id]['pause_button_overlay'] = None
            
    def start_pause_drag(self, event):
        """Start dragging the pause button marker"""
        self.dragging = True
        # Find which emulator's pause overlay is being dragged
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
        """Handle dragging the pause button marker"""
        if not self.dragging or not self.dragging_emulator:
            return
            
        overlay = self.emulators[self.dragging_emulator]['pause_button_overlay']
        if not overlay:
            return
            
        # Calculate new window position
        new_x = event.x_root - self.drag_start_x
        new_y = event.y_root - self.drag_start_y
        
        # Move the overlay to new position
        overlay.geometry(f"+{new_x}+{new_y}")
        
    def end_pause_drag(self, event):
        """End dragging and update pause button coordinates"""
        if not self.dragging or not self.dragging_emulator:
            return
            
        self.dragging = False
        
        overlay = self.emulators[self.dragging_emulator]['pause_button_overlay']
        if not overlay:
            return
            
        # Get final position and update pause button coordinates
        x = overlay.winfo_x()
        y = overlay.winfo_y()
        
        # Update pause button position
        self.emulators[self.dragging_emulator]['pause_button_pos'] = (x, y)
        
        print(f"🎯 Emu {self.dragging_emulator} - Pause button moved to: ({x}, {y})")
        
        self.dragging_emulator = None
        self.dragging_type = None
            
    def detect_drill_colors(self, emu_id):
        """Check if any drill colors are present in the detection area for a specific emulator"""
        try:
            emulator = self.emulators.get(emu_id)
            if not emulator or not emulator['active']:
                return True  # Skip detection if emulator is inactive
                
            # Take screenshot of detection area
            x1, y1, x2, y2 = emulator['detection_area']
            screenshot = ImageGrab.grab(bbox=(x1, y1, x2, y2))
            
            found_drill_colors = {}  # Track exact drill colors found and their pixel counts
            total_drill_pixels = 0
            
            # Check each pixel for drill colors
            for x in range(screenshot.width):
                for y in range(screenshot.height):
                    pixel_color = screenshot.getpixel((x, y))[:3]  # RGB only
                    
                    # Convert RGB to hex
                    hex_color = "#{:02x}{:02x}{:02x}".format(pixel_color[0], pixel_color[1], pixel_color[2])
                    
                    # Check if pixel matches any drill color (EXACT match only)
                    if hex_color in self.drill_colors:
                        if hex_color in found_drill_colors:
                            found_drill_colors[hex_color] += 1
                        else:
                            found_drill_colors[hex_color] = 1
                        total_drill_pixels += 1
                    else:
                        # Check for tolerance-based matches
                        for drill_color in self.drill_colors:
                            if self.hex_color_matches(hex_color, drill_color, self.color_tolerance):
                                if hex_color in found_drill_colors:
                                    found_drill_colors[hex_color] += 1
                                else:
                                    found_drill_colors[hex_color] = 1
                                total_drill_pixels += 1
                                break  # Found a match, no need to check other drill colors
            
            # Show the drill colors found and their pixel counts
            if found_drill_colors:
                # Use different pixel requirements for emulator 1 vs others
                if emu_id == 1:
                    required_pixels = 10  # Emulator 1 needs 10 pixels
                else:
                    required_pixels = self.min_drill_pixels  # Others use default 5 pixels
                
                # Check if we have enough drill pixels to consider drill present
                if total_drill_pixels >= required_pixels:
                    print(f"✅ Emu {emu_id} - Drill detected - {total_drill_pixels} px (min: {required_pixels})")
                    return True
                else:
                    print(f"❌ Emu {emu_id} - Drill Not detected - {total_drill_pixels} px (min: {required_pixels})")
                    return False
            else:
                # Use different pixel requirements for emulator 1 vs others
                if emu_id == 1:
                    required_pixels = 10  # Emulator 1 needs 10 pixels
                else:
                    required_pixels = self.min_drill_pixels  # Others use default 5 pixels
                    
                print(f"❌ Emu {emu_id} - Drill Not detected - 0 px (min: {required_pixels})")
                return False
            
        except Exception as e:
            print(f"Detection error for Emu {emu_id}: {e}")
            return True  # Assume drill is present on error

    def hex_color_matches(self, hex1, hex2, tolerance=10):
        """Check if two hex colors match within tolerance"""
        try:
            # Convert hex to RGB
            rgb1 = tuple(int(hex1[i:i+2], 16) for i in (1, 3, 5))
            rgb2 = tuple(int(hex2[i:i+2], 16) for i in (1, 3, 5))
            
            # Check if colors match within tolerance
            return all(abs(c1 - c2) <= tolerance for c1, c2 in zip(rgb1, rgb2))
        except:
            return False

    def play_alert_sound(self, emu_id):
        """Play a patterned alert sound for no drill detection (-- pause -- pause --)"""
        def sound_thread():
            try:
                # Pattern: -- pause -- pause --
                # Long beep = 500ms, Pause = 300ms
                
                # First sequence: --
                winsound.Beep(1000, 500)  # Long -
                time.sleep(0.1)
                winsound.Beep(1000, 500)  # Long -
                
                time.sleep(0.3)  # Pause
                
                # Second sequence: --
                winsound.Beep(1000, 500)  # Long -
                time.sleep(0.1)
                winsound.Beep(1000, 500)  # Long -
                
                time.sleep(0.3)  # Pause
                
                # Third sequence: --
                winsound.Beep(1000, 500)  # Long -
                time.sleep(0.1)
                winsound.Beep(1000, 500)  # Long -
                
            except Exception as e:
                print(f"Sound error for Emu {emu_id}: {e}")
        
        # Play sound in separate thread to avoid blocking
        threading.Thread(target=sound_thread, daemon=True).start()

    def perform_antijail_action(self, emu_id):
        """Perform the antijail action for a specific emulator"""
        with self.action_lock:  # Ensure only one emulator performs action at a time
            try:
                emulator = self.emulators.get(emu_id)
                if not emulator:
                    return
                    
                print(f"🔧 Emu {emu_id} - Drill not detected! Performing antijail action...")
                emulator['in_action'] = True
                
                # Play alert sound
                self.play_alert_sound(emu_id)
                
                # Update status
                status_label = getattr(self, f'status_label_{emu_id}', None)
                if status_label:
                    status_label.config(text="In Action", foreground='orange')
                
                # Click pause button twice
                pause_x, pause_y = emulator['pause_button_pos']
                pyautogui.click(pause_x, pause_y)
                time.sleep(0.1)
                pyautogui.click(pause_x, pause_y)
                
                # Wait 1 second
                time.sleep(1.0)
                
                # Perform movement sequence
                start_x, start_y = emulator['move_start_pos']
                pyautogui.moveTo(start_x, start_y)
                
                for i, (offset_x, offset_y) in enumerate(self.move_sequence_offsets):
                    move_x = start_x + offset_x
                    move_y = start_y + offset_y
                    pyautogui.dragTo(move_x, move_y, duration=0.2)
                    time.sleep(0.1)
                    
                print(f"✅ Emu {emu_id} - Antijail action completed")
                
                # Deactivate this emulator (stop detecting since it's paused)
                emulator['active'] = False
                emulator['in_action'] = False
                
                # Update status
                if status_label:
                    status_label.config(text="Paused", foreground='red')
                
            except Exception as e:
                print(f"Antijail action error for Emu {emu_id}: {e}")
                emulator['in_action'] = False

    def detection_loop(self, emu_id):
        """Main detection loop for a specific emulator"""
        while self.macro_running:
            try:
                emulator = self.emulators.get(emu_id)
                if not emulator:
                    break
                    
                if not emulator['active']:
                    # This emulator is paused, stop detecting
                    break
                
                if not self.detect_drill_colors(emu_id):
                    self.perform_antijail_action(emu_id)
                    
                time.sleep(3)  # Check every 4 seconds
                
            except Exception as e:
                print(f"Detection loop error for Emu {emu_id}: {e}")
                time.sleep(1.0)

    def start_macro(self):
        """Start the antijail macro for all emulators"""
        if self.macro_running:
            return
            
        # Reset all emulators to active state
        for emu_id in range(1, 5):
            self.emulators[emu_id]['active'] = True
            self.emulators[emu_id]['in_action'] = False
            status_label = getattr(self, f'status_label_{emu_id}', None)
            individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
            if status_label:
                status_label.config(text="Active", foreground='green')
            if individual_btn:
                individual_btn.config(text="⏸")
            
        self.macro_running = True
        self.main_status_label.config(text="Status: Running All Emulators")
        self.toggle_btn.config(text="⏸ Stop All")
        
        # Start detection threads for all emulators
        for emu_id in range(1, 5):
            thread = threading.Thread(target=self.detection_loop, args=(emu_id,), daemon=True)
            self.detection_threads[emu_id] = thread
            thread.start()
        
        print("🚀 Multi-emulator antijail macro started for all 4 emulators")
        
    def stop_macro(self):
        """Stop the antijail macro for all emulators"""
        self.macro_running = False
        
        # Update all emulator statuses
        for emu_id in range(1, 5):
            self.emulators[emu_id]['active'] = False
            self.emulators[emu_id]['in_action'] = False
            status_label = getattr(self, f'status_label_{emu_id}', None)
            individual_btn = getattr(self, f'individual_btn_{emu_id}', None)
            if status_label:
                status_label.config(text="Stopped", foreground='gray')
            if individual_btn:
                individual_btn.config(text="▶")
        
        self.main_status_label.config(text="Status: Stopped")
        self.toggle_btn.config(text="▶ Start All")
        
        # Clear detection threads
        self.detection_threads.clear()
        
        print("🛑 Multi-emulator antijail macro stopped")
        
    def toggle_macro(self):
        """Toggle the macro state (Start/Stop)"""
        if self.macro_running:
            self.stop_macro()
        else:
            self.start_macro()

    def toggle_individual_emulator(self, emu_id):
        """Toggle detection for a specific individual emulator"""
        emulator = self.emulators.get(emu_id)
        if not emulator:
            return
            
        individual_btn = getattr(self, f'individual_btn_{emu_id}')
        status_label = getattr(self, f'status_label_{emu_id}')
        
        if emulator['active']:
            # Stop this individual emulator
            emulator['active'] = False
            emulator['in_action'] = False
            individual_btn.config(text="▶")
            status_label.config(text="Stopped", foreground='gray')
            print(f"🛑 Emu {emu_id} - Individual stop")
        else:
            # Start this individual emulator
            if not self.macro_running:
                # If main macro isn't running, start it
                self.macro_running = True
                self.main_status_label.config(text="Status: Individual Emulators Running")
                self.toggle_btn.config(text="⏸ Stop All")
            
            emulator['active'] = True
            emulator['in_action'] = False
            individual_btn.config(text="⏸")
            status_label.config(text="Active", foreground='green')
            
            # Start detection thread for this emulator if not already running
            if emu_id not in self.detection_threads or not self.detection_threads[emu_id].is_alive():
                thread = threading.Thread(target=self.detection_loop, args=(emu_id,), daemon=True)
                self.detection_threads[emu_id] = thread
                thread.start()
                
            print(f"🚀 Emu {emu_id} - Individual start")
            
    def test_all_emulators(self):
        """Test the pause and move functionality for all emulators sequentially"""
        print("🧪 Testing all emulators sequentially...")
        
        def test_sequence():
            for emu_id in range(1, 5):
                print(f"🧪 Testing Emulator {emu_id}...")
                self.perform_antijail_action(emu_id)
                time.sleep(1)  # Brief pause between emulator tests
                
        test_thread = threading.Thread(target=test_sequence, daemon=True)
        test_thread.start()
            
    def run(self):
        """Run the application"""
        try:
            print("Antijail Macro started!")
            print("Controls:")
            print("- NumLock: Toggle macro on/off")
            print("- Use UI buttons for manual control")
            print("- Show detection area to see monitoring zone")
            
            self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
            self.root.mainloop()
            
        except KeyboardInterrupt:
            print("\nShutting down...")
        finally:
            self.cleanup()
            
    def on_closing(self):
        """Handle window closing"""
        self.cleanup()
        self.root.destroy()
        
    def cleanup(self):
        """Clean up resources"""
        self.macro_running = False
        for emu_id in range(1, 5):
            self.hide_detection_overlay(emu_id)
            self.hide_pause_button_marker(emu_id)
        
        if self.keyboard_listener:
            self.keyboard_listener.stop()
            
        print("Cleanup completed")

def main():
    try:
        # Disable pyautogui failsafe for smooth operation
        pyautogui.FAILSAFE = False
        pyautogui.PAUSE = 0.01
        
        macro = MultiEmulatorAntijailMacro()
        macro.run()
        
    except Exception as e:
        print(f"Application error: {e}")
        
if __name__ == "__main__":
    main() 