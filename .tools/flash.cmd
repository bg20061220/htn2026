@echo off
rem Build and flash the OpenBot firmware onto the Freenove ESP32-S3-WROOM board.
rem Usage: .tools\flash.cmd COM5      (find the port with .tools\arduino.cmd board list)
rem
rem The board has two USB-C sockets: the UART one (CH343 bridge) and the native USB one.
rem Either works for flashing; use the UART socket for the phone link.
setlocal
set FQBN=esp32:esp32:esp32s3:PartitionScheme=huge_app
set SKETCH=%~dp0..\OpenBot\firmware\openbot
if "%~1"=="" (
    echo usage: flash.cmd ^<serial-port^>    e.g. flash.cmd COM5
    exit /b 2
)
"%~dp0cli\arduino-cli.exe" --config-file "%~dp0..\arduino-cli.yaml" compile --fqbn %FQBN% "%SKETCH%" || exit /b 1
"%~dp0cli\arduino-cli.exe" --config-file "%~dp0..\arduino-cli.yaml" upload -p %1 --fqbn %FQBN% "%SKETCH%"
