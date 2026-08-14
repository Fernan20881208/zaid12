# Test data

The functional test builds its `WifiConfigStoreData` fixture in memory. It uses
the public WPA PBKDF2 vector `password` / `IEEE` and an explicitly fictional
second profile. No file, SSID, credential, MAC address, or log captured from a
device is stored in this directory or used by GitHub Actions.
