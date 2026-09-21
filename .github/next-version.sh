#!/usr/bin/env bash
# Prints the next mod_version. Usage: next-version.sh <current> [auto|patch|minor|major]
# With "auto" (the default) the commit log is read from stdin and decides the bump:
#   "feat!:" / "BREAKING CHANGE" -> major, "feat:" -> minor, anything else -> patch.
# Self-test: bash .github/next-version.sh --self-test
set -euo pipefail

next_version() {
  local current=$1 bump=${2:-auto} log major minor patch
  if [ "$bump" = auto ]; then
    log=$(cat)
    if grep -qE '^BREAKING CHANGE|^[a-z]+(\(.+\))?!:' <<<"$log"; then bump=major
    elif grep -qE '^feat(\(.+\))?:' <<<"$log"; then bump=minor
    else bump=patch
    fi
  fi
  IFS=. read -r major minor patch <<<"$current"
  case $bump in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) echo "unknown bump: $bump" >&2; return 2 ;;
  esac
  echo "$major.$minor.$patch"
}

self_test() {
  local got want fail=0
  check() { # check <want> <current> <bump> [log]
    want=$1; got=$(printf '%s' "${4-}" | next_version "$2" "$3")
    [ "$got" = "$want" ] || { echo "FAIL: $2 $3 '${4-}' -> $got, want $want" >&2; fail=1; }
  }
  check 0.1.1 0.1.0 auto  'ME请求器整合进自动化'
  check 0.2.0 0.1.0 auto  'feat: Adding Alert'
  check 1.0.0 0.1.0 auto  'feat(api)!: drop v0 endpoints'
  check 1.0.0 0.1.0 auto  'fix: x'$'\n\n''BREAKING CHANGE: y'
  check 0.2.0 0.1.0 auto  'fix: a'$'\n''feat: b'          # any feat in the range wins
  check 0.1.1 0.1.0 auto  'docs: mention feat: in prose'  # only line starts count
  check 0.1.1 0.1.0 patch ''
  check 2.0.0 1.9.3 major ''
  check 1.10.0 1.9.3 minor ''
  [ $fail = 0 ] && echo "next-version.sh: all checks passed"
  return $fail
}

if [ "${1-}" = --self-test ]; then self_test; else next_version "$@"; fi
