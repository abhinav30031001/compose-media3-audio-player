#!/usr/bin/env python3
"""
sign_config.py - Standalone utility to sign client_versions.json for YT Music.

Security features (Task B1):
  1. Passphrase encryption for private key PEM (BestAvailableEncryption).
  2. Refuses to write private keys inside a git repository.
  3. Sets restrictive 0600 file permissions.
"""

import os
import sys
import json
import time
import base64
import argparse
import getpass

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519
    from cryptography.hazmat.primitives import serialization
except ImportError:
    print("[-] 'cryptography' library required. Install with: pip install cryptography")
    sys.exit(1)

def is_inside_git_repo(path: str) -> bool:
    cur = os.path.abspath(path)
    while cur and cur != os.path.dirname(cur):
        if os.path.exists(os.path.join(cur, ".git")):
            return True
        cur = os.path.dirname(cur)
    return False

def generate_keypair(out_path: str = "ed25519_private.pem"):
    if is_inside_git_repo(os.path.dirname(os.path.abspath(out_path))):
        print(f"[-] SECURITY ERROR: Refusing to write private key inside a git repository ({out_path})!")
        print("[-] Please specify an output path OUTSIDE the repository (e.g. ~/ed25519_private.pem).")
        sys.exit(1)

    print("[*] Generating Ed25519 keypair...")
    priv = ed25519.Ed25519PrivateKey.generate()
    pub = priv.public_key()
    pub_raw = pub.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw
    )
    pub_b64 = base64.b64encode(pub_raw).decode('utf-8')

    passphrase = getpass.getpass("Enter passphrase to encrypt private key PEM (or press Enter for unencrypted): ")
    if passphrase:
        encryption = serialization.BestAvailableEncryption(passphrase.encode('utf-8'))
    else:
        encryption = serialization.NoEncryption()

    priv_pem = priv.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=encryption
    )

    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    mode = 0o600
    with os.fdopen(os.open(out_path, flags, mode), "wb") as f:
        f.write(priv_pem)

    print(f"[+] Private key safely saved to: {out_path} (mode 0600)")
    print(f"[+] Public key (Base64) to embed in app: {pub_b64}")

def canonical_payload(vos: str, avr: str, rmx: str, ts: int) -> bytes:
    return f"visionos={vos}&android_vr={avr}&remix={rmx}&ts={ts}".encode('utf-8')

def sign_config(config_path: str, key_path: str):
    if not os.path.exists(config_path):
        print(f"[-] Config file not found: {config_path}")
        sys.exit(1)
    if not os.path.exists(key_path):
        print(f"[-] Key file not found: {key_path}")
        sys.exit(1)

    with open(key_path, "rb") as f:
        key_bytes = f.read()

    try:
        priv = serialization.load_pem_private_key(key_bytes, password=None)
    except TypeError:
        passphrase = getpass.getpass("Enter passphrase for private key: ")
        priv = serialization.load_pem_private_key(key_bytes, password=passphrase.encode('utf-8'))

    with open(config_path, "r") as f:
        data = json.load(f)

    vos = str(data.get("visionos", "")).strip()
    avr = str(data.get("android_vr", "")).strip()
    rmx = str(data.get("remix", "")).strip()
    ts = int(data.get("ts", time.time()))

    payload = canonical_payload(vos, avr, rmx, ts)
    sig = priv.sign(payload)
    sig_b64 = base64.b64encode(sig).decode('utf-8')

    data["ts"] = ts
    data["sig"] = sig_b64

    with open(config_path, "w") as f:
        json.dump(data, f, indent=2)

    print(f"[+] Successfully signed {config_path}")
    print(f"[+] Canonical Payload: {payload.decode('utf-8')}")
    print(f"[+] Signature (Base64): {sig_b64}")

def main():
    parser = argparse.ArgumentParser(description="Sign client_versions.json")
    parser.add_argument("--generate-key", action="store_true", help="Generate a new Ed25519 keypair")
    parser.add_argument("--out-key", type=str, default="ed25519_private.pem", help="Output path for private key")
    parser.add_argument("--config", type=str, help="Path to client_versions.json")
    parser.add_argument("--key", type=str, default="ed25519_private.pem", help="Path to private key PEM")
    args = parser.parse_args()

    if args.generate_key:
        generate_keypair(args.out_key)
    elif args.config:
        sign_config(args.config, args.key)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
