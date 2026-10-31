"""Make the ADMIN_KEY_HASH value for Railway.

    python deploy/admin-hash.py             # type a password (hidden), twice
    python deploy/admin-hash.py --generate  # make a strong random password and print it once

Only the printed hash goes into Railway. The password itself is never stored anywhere by the app;
keep it in a password manager.
"""

import base64
import getpass
import hashlib
import secrets
import sys

ITERATIONS = 600_000  # OWASP's recommendation for PBKDF2-HMAC-SHA256
MIN_LENGTH = 16


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def main() -> None:
    if "--generate" in sys.argv:
        password = secrets.token_urlsafe(24)
        print(f"Your new admin password (save it in a password manager now, it is not shown again):\n\n  {password}\n")
    else:
        password = getpass.getpass("Admin password: ")
        if getpass.getpass("Same password again: ") != password:
            sys.exit("The two passwords differ.")
        if len(password) < MIN_LENGTH:
            sys.exit(f"Use at least {MIN_LENGTH} characters, or run with --generate.")

    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, ITERATIONS)
    print("Set this variable on the app service in Railway:\n")
    print(f"  ADMIN_KEY_HASH=pbkdf2-sha256:{ITERATIONS}:{b64(salt)}:{b64(digest)}")


if __name__ == "__main__":
    main()
