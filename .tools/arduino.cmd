@echo off
rem Run the repo-local arduino-cli with the repo-local toolchain and board index.
rem Usage: .tools\arduino.cmd <arduino-cli arguments>
"%~dp0cli\arduino-cli.exe" --config-file "%~dp0..\arduino-cli.yaml" %*
