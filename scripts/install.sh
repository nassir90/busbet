#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_FILE="$HOME/.local/share/applications/busbet-icon-tool.desktop"

mkdir -p "$HOME/.local/share/applications"

cat > "$DESKTOP_FILE" << EOF
[Desktop Entry]
Name=BusBet Icon Tool
Comment=Android icon manager for tfi-app
Exec=bash -c 'cd "$SCRIPT_DIR" && uv run icon-tool'
Icon=image-x-generic
Terminal=false
Type=Application
Categories=Utility;
EOF

echo "Installed to $DESKTOP_FILE"
echo ""
echo "For i3: add to ~/.config/i3/config:"
echo "  for_window [class=\"icon-tool\"] floating enable"
