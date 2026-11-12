#!/bin/sh
# Installs only this DSH skill into an explicitly selected, existing DSH home.
set -eu

usage() { printf '%s\n' 'Usage: sh install.sh --dsh-home /absolute/dsh-home [--dry-run]' >&2; exit 2; }
target_home=''
dry_run=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dsh-home) [ "$#" -ge 2 ] || usage; target_home=$2; shift 2 ;;
        --dry-run) dry_run=true; shift ;;
        *) usage ;;
    esac
done
case "$target_home" in /*) ;; *) usage ;; esac
[ -d "$target_home" ] || { printf '%s\n' 'DSH home does not exist; select the current engine home explicitly.' >&2; exit 1; }
target_home=$(CDPATH= cd -P "$target_home" && pwd)
source_dir=$(CDPATH= cd -P "$(dirname "$0")/dsh-phone-routines" && pwd)
destination="$target_home/skills/dsh-phone-routines"
for item in SKILL.md routines.md; do
    [ -f "$source_dir/$item" ] || { printf 'Missing source: %s\n' "$item" >&2; exit 1; }
done
if [ -L "$destination" ]; then
    printf '%s\n' 'Refusing to replace a linked skill directory.' >&2
    exit 1
fi
if [ -e "$destination" ]; then
    if cmp -s "$source_dir/SKILL.md" "$destination/SKILL.md" && cmp -s "$source_dir/routines.md" "$destination/routines.md"; then
        printf 'Unchanged: %s\n' "$destination"
        exit 0
    fi
    printf 'Already exists, preserved: %s\nBack up or rename that directory before installing a different version.\n' "$destination" >&2
    exit 1
fi
if [ "$dry_run" = true ]; then
    printf 'Would install DSH skill: %s\nNo files changed.\n' "$destination"
    exit 0
fi
mkdir -p "$target_home/skills"
mkdir "$destination"
cp "$source_dir/routines.md" "$destination/routines.md"
# Commit the discoverable entrypoint after its referenced file exists.
cp "$source_dir/SKILL.md" "$destination/SKILL.md"
printf 'Installed: %s\nRefresh DSH skills or start a new conversation, then use /dsh-phone-routines.\n' "$destination"
