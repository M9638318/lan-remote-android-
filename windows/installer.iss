#define MyAppName "LAN Touchpad"
#ifndef MyAppVersion
  #define MyAppVersion "2.1.0"
#endif
#define MyAppPublisher "LAN Touchpad"
#define MyAppExeName "LAN-Touchpad.exe"

[Setup]
AppId={{B48F5A72-2C8E-4B91-A2C1-7F91D73B9A21}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\LAN Touchpad
DefaultGroupName=LAN Touchpad
DisableProgramGroupPage=yes
OutputDir=dist-installer
OutputBaseFilename=LAN-Touchpad-Setup-v{#MyAppVersion}
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
ChangesAssociations=no
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加快捷方式："; Flags: checkedonce
Name: "autostart"; Description: "登录 Windows 时自动启动 LAN Touchpad"; GroupDescription: "启动选项："; Flags: checkedonce

[Files]
Source: "dist\LAN-Touchpad.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "README_INSTALL.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\LAN Touchpad"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\LAN Touchpad"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "LAN Touchpad"; ValueData: """{app}\{#MyAppExeName}"""; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "立即启动 LAN Touchpad"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
; Deliberately do not delete %LOCALAPPDATA%\LAN Touchpad: received files and settings survive uninstall.
