# Validation record

## v2.1.0 Windows GUI / installer update

Automated checks completed in the available Linux build environment:

- `python -m py_compile windows/server.py windows/gui.py windows/select_python.py`: passed.
- `python -m unittest discover -s tests -v`: 23 tests passed, including the existing server protocol/file-transfer tests and Windows packaging/installer source checks.
- GitHub Actions workflow YAML parsed successfully with a YAML parser.
- Existing Android XML/resource checks remain included in the test suite.

Not yet verified in this environment:

- PyInstaller GUI EXE generation and runtime behavior on Windows 10/11.
- Inno Setup compilation and interactive installer behavior.
- Real Windows firewall prompt, Windows startup registry behavior, and Android-to-Windows hardware acceptance.

These Windows-only checks are delegated to the Windows GitHub Actions runner and then require a manual installation/device test. Passing Python unit tests does not by itself prove that the Windows EXE or installer works on a real PC.

## Data and safety behavior

- Source-mode server data remains beside `windows/server.py` under `windows/received`.
- Frozen EXE received files are stored in `%LOCALAPPDATA%\\LAN Touchpad\\received`, outside Program Files.
- The Inno Setup uninstall script intentionally does not delete this user-data directory.
- The service binds to `0.0.0.0:8765`; only use on a trusted private LAN. Do not expose the port to the public Internet.

## Existing Android/server tests

The 17 existing Android resource and Windows server tests continue to pass as part of the 23-test suite. The protocol v2 behavior was not intentionally changed by adding the GUI process wrapper.
