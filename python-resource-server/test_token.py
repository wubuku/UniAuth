#!/usr/bin/env python3
"""Manually validate a token without echoing it or its claims."""

from getpass import getpass

from app import validate_token


def main() -> int:
    token = getpass("Token: ").strip()
    if not token:
        print("Token validation failed")
        return 1

    valid, _ = validate_token(token)
    if valid:
        print("Token validation succeeded")
        return 0

    print("Token validation failed")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
