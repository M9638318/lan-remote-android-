from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from pathlib import Path

import server

BG = "#f3f5f9"
CARD = "#ffffff"
TEXT = "#172033"
MUTED = "#667085"
BLUE = "#2563eb"
GREEN = "#16834a"
AMBER = "#b7791f"
RED = "#c0392b"
PORT = server.PORT


def worker_command() -> list[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable, "--worker"]
    return [sys.executable, "-u", str(Path(__file__).with_name("server.py")), "--worker"]


class LanTouchpadApp:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("LAN Touchpad · Windows 控制中心")
        self.root.geometry("940x700")
        self.root.minsize(820, 620)
        self.root.configure(bg=BG)
        self.process: subprocess.Popen[str] | None = None
        self.events: queue.Queue[str] = queue.Queue()
        self.running = False
        self.connected = False
        self.addresses = server.private_ipv4_addresses()
        self._style()
        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(120, self._poll)
        self.root.after(350, self.start_server)

    def _style(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=CARD)
        style.configure("TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("Title.TLabel", background=BG, foreground=TEXT, font=("Segoe UI Variable Display", 23, "bold"))
        style.configure("Sub.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 10))
        style.configure("CardTitle.TLabel", background=CARD, foreground=TEXT, font=("Segoe UI", 11, "bold"))
        style.configure("CardText.TLabel", background=CARD, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=(14, 9))
        style.configure("Treeview", font=("Segoe UI", 9), rowheight=25)

    def _card(self, parent: ttk.Frame, title: str, subtitle: str | None = None) -> ttk.Frame:
        frame = ttk.Frame(parent, style="Card.TFrame", padding=16)
        frame.pack(fill="x", pady=(0, 12))
        ttk.Label(frame, text=title, style="CardTitle.TLabel").pack(anchor="w")
        if subtitle:
            ttk.Label(frame, text=subtitle, style="CardText.TLabel").pack(anchor="w", pady=(4, 10))
        return frame

    def _build(self) -> None:
        shell = ttk.Frame(self.root, padding=(26, 22, 26, 18))
        shell.pack(fill="both", expand=True)
        header = ttk.Frame(shell)
        header.pack(fill="x", pady=(0, 18))
        ttk.Label(header, text="LAN Touchpad", style="Title.TLabel").pack(anchor="w")
        ttk.Label(header, text="Windows 远程控制 · 本地局域网控制中心", style="Sub.TLabel").pack(anchor="w", pady=(3, 0))

        status = self._card(shell, "服务状态", "管理 Windows 端 WebSocket 服务")
        line = ttk.Frame(status, style="Card.TFrame")
        line.pack(fill="x")
        self.dot = tk.Label(line, text="●", bg=CARD, fg=AMBER, font=("Segoe UI", 18))
        self.dot.pack(side="left", padx=(0, 8))
        self.status_var = tk.StringVar(value="正在初始化服务…")
        ttk.Label(line, textvariable=self.status_var, style="CardTitle.TLabel").pack(side="left")
        self.server_btn = ttk.Button(line, text="停止服务", style="Accent.TButton", command=self.toggle_server)
        self.server_btn.pack(side="right")

        network = self._card(shell, "局域网连接地址", "让 Android 手机或平板连接到同一 Wi-Fi / 局域网")
        self.address_box = tk.Listbox(network, height=3, font=("Consolas", 10), relief="flat", bg="#f7f9fc", fg=TEXT, activestyle="none")
        self.address_box.pack(fill="x", pady=(0, 8))
        self._refresh_addresses()
        ttk.Button(network, text="复制选中的地址", command=self.copy_address).pack(anchor="e")

        details = ttk.Frame(shell)
        details.pack(fill="x")
        left = self._card(details, "设备连接", "当前最多支持一个 Android 控制端")
        left.pack(side="left", fill="both", expand=True, padx=(0, 6))
        self.device_var = tk.StringVar(value="尚无设备连接")
        ttk.Label(left, textvariable=self.device_var, style="CardText.TLabel").pack(anchor="w", pady=(8, 0))
        right = self._card(details, "文件接收目录", "Android 上传到电脑的文件保存位置")
        right.pack(side="left", fill="both", expand=True, padx=(6, 0))
        self.receive_var = tk.StringVar(value=str(server.RECEIVED_DIR))
        ttk.Label(right, textvariable=self.receive_var, style="CardText.TLabel", wraplength=360).pack(anchor="w", pady=(8, 8))
        ttk.Button(right, text="打开接收文件夹", command=self.open_received).pack(anchor="e")

        log_card = self._card(shell, "运行日志", "实时显示服务启动、设备连接和错误信息")
        log_frame = ttk.Frame(log_card, style="Card.TFrame")
        log_frame.pack(fill="both", expand=True, pady=(8, 0))
        self.log = tk.Text(log_frame, height=10, wrap="word", bg="#101828", fg="#e5e7eb", insertbackground="white", relief="flat", font=("Consolas", 9), state="disabled")
        scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        actions = ttk.Frame(shell)
        actions.pack(fill="x", pady=(2, 0))
        ttk.Button(actions, text="发送电脑文件到 Android", command=self.send_file).pack(side="left")
        ttk.Button(actions, text="刷新网络地址", command=self.refresh_addresses).pack(side="left", padx=8)
        self.autostart_var = tk.BooleanVar(value=self._autostart_enabled())
        ttk.Checkbutton(actions, text="登录 Windows 时自动启动", variable=self.autostart_var, command=self.toggle_autostart).pack(side="left", padx=8)
        ttk.Label(actions, text="端口：8765  ·  仅建议在可信专用网络使用", style="Sub.TLabel").pack(side="right")

    def _refresh_addresses(self) -> None:
        self.address_box.delete(0, tk.END)
        self.addresses = server.private_ipv4_addresses()
        for address in self.addresses:
            self.address_box.insert(tk.END, f"ws://{address}:{PORT}")
        if not self.addresses:
            self.address_box.insert(tk.END, "未检测到 IPv4 地址，请检查网络连接")

    def refresh_addresses(self) -> None:
        self._refresh_addresses()
        self._append_log("[网络] 已刷新本机 IPv4 地址")

    def copy_address(self) -> None:
        selected = self.address_box.curselection()
        if not selected or not self.addresses:
            messagebox.showinfo("LAN Touchpad", "请先选择一个有效的连接地址。")
            return
        value = f"ws://{self.addresses[selected[0]]}:{PORT}"
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        self.status_var.set("连接地址已复制")

    def _append_log(self, line: str) -> None:
        self.log.configure(state="normal")
        self.log.insert(tk.END, line.rstrip() + "\n")
        self.log.see(tk.END)
        self.log.configure(state="disabled")

    def start_server(self) -> None:
        if self.process and self.process.poll() is None:
            return
        try:
            env = os.environ.copy()
            env["PYTHONIOENCODING"] = "utf-8"
            env["PYTHONUNBUFFERED"] = "1"
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            self.process = subprocess.Popen(
                worker_command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                bufsize=1, creationflags=creationflags, cwd=str(server.APP_DIR), env=env,
            )
            self.running = True
            self.connected = False
            self.status_var.set("服务启动中…")
            self.dot.configure(fg=AMBER)
            self.server_btn.configure(text="停止服务")
            threading.Thread(target=self._read_output, args=(self.process,), daemon=True).start()
            self._append_log("[GUI] 正在启动 Windows 远程控制服务")
        except Exception as exc:
            self.running = False
            self.status_var.set("服务启动失败")
            self.dot.configure(fg=RED)
            messagebox.showerror("LAN Touchpad", f"无法启动服务：\n{exc}")

    def _read_output(self, process: subprocess.Popen[str]) -> None:
        assert process.stdout is not None
        for line in process.stdout:
            self.events.put(line.rstrip())
        code = process.wait()
        self.events.put(f"[GUI] 服务进程已退出，退出代码：{code}")
        self.events.put("__PROCESS_EXITED__")

    def _send_command(self, command: str) -> None:
        if not self.process or self.process.poll() is not None or not self.process.stdin:
            return
        try:
            self.process.stdin.write(command + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as exc:
            self._append_log(f"[错误] 无法向服务进程发送命令：{exc}")

    def stop_server(self) -> None:
        if self.process and self.process.poll() is None:
            self._send_command("quit")
            self.status_var.set("正在停止服务…")
            self.dot.configure(fg=AMBER)

    def toggle_server(self) -> None:
        if self.process and self.process.poll() is None:
            self.stop_server()
            self.server_btn.configure(text="启动服务")
        else:
            self.start_server()

    def send_file(self) -> None:
        path = filedialog.askopenfilename(title="选择要发送到 Android 的文件")
        if not path:
            return
        if not self.connected:
            messagebox.showinfo("LAN Touchpad", "请先让 Android 设备连接到本机，再发送文件。")
            return
        safe = path.replace('"', '')
        self._send_command(f'send "{safe}"')
        self._append_log(f"[文件] 已提交发送任务：{Path(path).name}")

    def _autostart_enabled(self) -> bool:
        if os.name != "nt":
            return False
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_READ) as key:
                winreg.QueryValueEx(key, "LAN Touchpad")
            return True
        except OSError:
            return False

    def toggle_autostart(self) -> None:
        if os.name != "nt":
            return
        try:
            import winreg
            key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE) as key:
                if self.autostart_var.get():
                    if getattr(sys, "frozen", False):
                        command = f'"{sys.executable}"'
                    else:
                        command = f'"{sys.executable}" "{Path(__file__).resolve()}"'
                    winreg.SetValueEx(key, "LAN Touchpad", 0, winreg.REG_SZ, command)
                    self._append_log("[设置] 已启用登录 Windows 自动启动")
                else:
                    try:
                        winreg.DeleteValue(key, "LAN Touchpad")
                    except FileNotFoundError:
                        pass
                    self._append_log("[设置] 已关闭登录 Windows 自动启动")
        except OSError as exc:
            self.autostart_var.set(not self.autostart_var.get())
            messagebox.showerror("LAN Touchpad", f"修改开机启动设置失败：\n{exc}")

    def open_received(self) -> None:
        server.RECEIVED_DIR.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(str(server.RECEIVED_DIR))  # type: ignore[attr-defined]
        except Exception as exc:
            messagebox.showerror("LAN Touchpad", f"无法打开文件夹：\n{exc}")

    def _poll(self) -> None:
        try:
            while True:
                line = self.events.get_nowait()
                if line == "__PROCESS_EXITED__":
                    self.running = False
                    self.connected = False
                    self.status_var.set("服务已停止")
                    self.dot.configure(fg="#98a2b3")
                    self.device_var.set("尚无设备连接")
                    self.server_btn.configure(text="启动服务")
                    continue
                self._append_log(line)
                if "WebSocket 已启动" in line or "监听：ws://" in line:
                    self.status_var.set("服务运行中 · 等待 Android 连接")
                    self.dot.configure(fg=GREEN)
                    self.running = True
                if "Android 已连接" in line:
                    self.connected = True
                    self.status_var.set("已连接 · 远程控制就绪")
                    self.dot.configure(fg=GREEN)
                    self.device_var.set(line)
                if "Android 已断开" in line:
                    self.connected = False
                    self.status_var.set("服务运行中 · 等待 Android 连接")
                    self.dot.configure(fg=AMBER)
                    self.device_var.set("尚无设备连接")
                if "Address already in use" in line or "WinError 10048" in line:
                    self.status_var.set("端口 8765 已被占用")
                    self.dot.configure(fg=RED)
        except queue.Empty:
            pass
        if self.root.winfo_exists():
            self.root.after(120, self._poll)

    def close(self) -> None:
        if self.process and self.process.poll() is None:
            self._send_command("quit")
            try:
                self.process.wait(timeout=2.5)
            except subprocess.TimeoutExpired:
                self.process.terminate()
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    if "--worker" in sys.argv:
        import asyncio
        try:
            asyncio.run(server.main())
        except KeyboardInterrupt:
            pass
    else:
        LanTouchpadApp().run()
