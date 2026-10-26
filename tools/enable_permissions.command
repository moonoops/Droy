#!/bin/sh
set -u
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python_command=${DROY_PYTHON:-}
if [ -z "$python_command" ]; then
    python_command=$(command -v python3 || command -v python || true)
fi
result=2
if [ -n "$python_command" ] && "$python_command" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)'; then
    "$python_command" "$script_dir/enable_permissions.py" "$@"
    result=$?
else
    printf '需要 Python 3.8 或更新版本。请安装或设置 DROY_PYTHON：https://www.python.org/downloads/\n' >&2
fi
if [ "$#" -eq 0 ] && [ -t 0 ]; then
    printf '\n按回车关闭…'
    read -r reply || true
fi
exit "$result"
