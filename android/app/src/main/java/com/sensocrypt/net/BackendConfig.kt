package com.sensocrypt.net

/**
 * Dev backend location. Uses the Mac's LAN IP rather than 127.0.0.1 so any phone on the
 * same WiFi network can reach it directly -- unlike `adb reverse`, this doesn't depend on
 * a USB connection staying up, which matters once more than one device is involved.
 * Update this if the dev machine's IP changes (check with `ipconfig getifaddr en0`).
 */
const val BACKEND_HOST = "192.168.1.2:8000"
