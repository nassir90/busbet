#!/bin/bash
set -e

DESKTOP_FILE="$HOME/.local/share/applications/busbet-icon-tool.desktop"

if [ -f "$DESKTOP_FILE" ]; then
    rm "$DESKTOP_FILE"
    echo "Removed $DESKTOP_FILE"
else
    echo "Not installed (no desktop entry found at $DESKTOP_FILE)"
fi
