#!/bin/sh
# Removes the installation made by install.sh from this package (same options: --app-dir, --bin-dir, --prefix ...).
# Only the notebook-ai installation owned by the installer is removed; your projects and exports are never touched.
here=$(cd "$(dirname "$0")" 2>/dev/null && pwd -P) || { echo "uninstall.sh: cannot resolve its own directory" >&2; exit 1; }
exec sh "$here/install.sh" --uninstall "$@"
