#!/usr/bin/env python3
"""Functional tests using synthetic Wi-Fi data only."""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile


EXPECTED_PMK = (
    "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e"
)

FIXTURE = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<WifiConfigStoreData>
  <NetworkList>
    <Network>
      <WifiConfiguration>
        <string name="SSID">&quot;IEEE&quot;</string>
        <string name="PreSharedKey">&quot;password&quot;</string>
        <int name="SecurityType" value="2" />
        <int name="Status" value="1" />
      </WifiConfiguration>
      <NetworkStatus>
        <string name="SelectionStatus">NETWORK_SELECTION_PERMANENTLY_DISABLED</string>
        <string name="DisableReason">NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD</string>
      </NetworkStatus>
    </Network>
    <Network>
      <WifiConfiguration>
        <string name="SSID">&quot;UNCHANGED&quot;</string>
        <string name="PreSharedKey">&quot;not-a-real-password&quot;</string>
        <int name="SecurityType" value="2" />
        <int name="Status" value="2" />
      </WifiConfiguration>
      <NetworkStatus>
        <string name="SelectionStatus">NETWORK_SELECTION_ENABLED</string>
        <string name="DisableReason">NETWORK_SELECTION_ENABLE</string>
      </NetworkStatus>
    </Network>
  </NetworkList>
</WifiConfigStoreData>
"""


def run(binary: pathlib.Path, source: pathlib.Path, destination: pathlib.Path) -> str:
    completed = subprocess.run(
        [str(binary), str(source), str(destination)],
        check=True,
        capture_output=True,
        text=True,
    )
    if completed.stderr:
        raise AssertionError("patcher unexpectedly wrote to stderr")
    return completed.stdout


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} PATCHER", file=sys.stderr)
        return 2

    binary = pathlib.Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory(prefix="duchamp-wifi-test-") as temp_dir:
        root = pathlib.Path(temp_dir)
        original = root / "input.xml"
        first = root / "first.xml"
        second = root / "second.xml"
        original.write_text(FIXTURE, encoding="utf-8")

        assert run(binary, original, first) == "patched=1\n"
        output = first.read_text(encoding="utf-8")
        assert f'<string name="PreSharedKey">{EXPECTED_PMK}</string>' in output
        assert '<int name="Status" value="2" />' in output
        assert "NETWORK_SELECTION_PERMANENTLY_DISABLED" not in output
        assert "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD" not in output
        assert output.count("NETWORK_SELECTION_ENABLED") == 2
        assert output.count("NETWORK_SELECTION_ENABLE</string>") == 2
        assert "&quot;not-a-real-password&quot;" in output

        assert run(binary, first, second) == "patched=0\n"
        assert first.read_bytes() == second.read_bytes()

    print("functional tests ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
