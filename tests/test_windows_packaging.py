from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = ROOT / "windows"


class WindowsPackagingTests(unittest.TestCase):
    def test_build_and_start_batch_files_exist(self):
        self.assertTrue((WINDOWS / "build_exe.bat").is_file())
        self.assertTrue((WINDOWS / "start_server.bat").is_file())

    def test_build_script_targets_a_windowed_gui_exe(self):
        script = (WINDOWS / "build_exe.bat").read_text(encoding="utf-8")
        self.assertIn("--onefile", script)
        self.assertIn("--windowed", script)
        self.assertIn("gui.py", script)
        self.assertIn("LAN-Touchpad.exe", script)
        self.assertIn("requirements-build.txt", script)
        self.assertTrue((WINDOWS / "select_python.py").is_file())
        installer = (WINDOWS / "installer.iss").read_text(encoding="utf-8")
        self.assertIn("DefaultDirName={autopf}\\LAN Touchpad", installer)
        self.assertIn("desktopicon", installer)
        self.assertIn("autostart", installer)
        self.assertIn("LAN-Touchpad.exe", installer)

    def test_gui_and_installer_sources_are_present(self):
        gui = (WINDOWS / "gui.py").read_text(encoding="utf-8")
        installer = (WINDOWS / "installer.iss").read_text(encoding="utf-8")
        workflow = (ROOT / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
        self.assertIn("停止服务", gui)
        self.assertIn("复制选中的地址", gui)
        self.assertIn("toggle_autostart", gui)
        self.assertIn("DefaultDirName={autopf}\\LAN Touchpad", installer)
        self.assertIn("LAN-Touchpad-Setup-v", installer)
        self.assertIn("Inno Setup", workflow)

    def test_frozen_build_keeps_received_files_in_user_profile(self):
        source = (WINDOWS / "server.py").read_text(encoding="utf-8")
        self.assertIn('os.environ["LOCALAPPDATA"]', source)
        self.assertIn('"LAN Touchpad"', source)

    def test_source_mode_received_directory_is_next_to_server(self):
        import importlib.util
        import sys
        import types

        # The project server is platform-specific; use stubs for the GUI modules
        # so this path calculation can be tested on any CI operating system.
        pyautogui = types.ModuleType("pyautogui")
        for name in ("keyDown", "keyUp", "mouseDown", "mouseUp", "moveRel", "moveTo", "click", "scroll", "doubleClick", "press", "hotkey"):
            setattr(pyautogui, name, lambda *args, **kwargs: None)
        mss = types.ModuleType("mss")
        mss.mss = lambda: None
        old_pyautogui = sys.modules.get("pyautogui")
        old_mss = sys.modules.get("mss")
        sys.modules["pyautogui"] = pyautogui
        sys.modules["mss"] = mss
        try:
            path = WINDOWS / "server.py"
            spec = importlib.util.spec_from_file_location("packaging_path_test_server", path)
            module = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = module
            spec.loader.exec_module(module)
            self.assertEqual(module.RECEIVED_DIR, WINDOWS / "received")
        finally:
            sys.modules.pop("packaging_path_test_server", None)
            if old_pyautogui is None:
                sys.modules.pop("pyautogui", None)
            else:
                sys.modules["pyautogui"] = old_pyautogui
            if old_mss is None:
                sys.modules.pop("mss", None)
            else:
                sys.modules["mss"] = old_mss

    def test_build_dependencies_include_runtime_and_packager(self):
        requirements = (WINDOWS / "requirements-build.txt").read_text(encoding="utf-8")
        self.assertIn("-r requirements.txt", requirements)
        self.assertIn("-r requirements-gamepad.txt", requirements)
        self.assertIn("PyInstaller==6.22.3", requirements)
        workflow = (ROOT / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
        self.assertIn("windows\\build_exe.bat", workflow)
        self.assertIn("installer.iss", workflow)
        self.assertIn("LAN-Touchpad-Setup-v2.1.0", workflow)
        selector = (WINDOWS / "select_python.py").read_text(encoding="utf-8")
        self.assertIn('os.environ.get("CI", "").lower() == "true"', selector)


if __name__ == "__main__":
    unittest.main()
