#!/usr/bin/env bash
#
# gen-cert.sh — create a self-signed TLS keystore so phones can use the mic
# (browsers require a secure origin for getUserMedia).
#
# Usage:
#   ./scripts/gen-cert.sh            # uses your LAN IP automatically
#   ./scripts/gen-cert.sh 192.168.1.50
#
# Then run the app with:
#   TLS_KEYSTORE=$PWD/keystore.p12 TLS_PASSWORD=changeit ./scripts/run.sh
# and open https://<IP>:8443 on the phone (accept the self-signed warning).
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
IP="${1:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
PASS="${TLS_PASSWORD:-changeit}"
OUT="$HERE/keystore.p12"

echo "Generating self-signed cert for IP: $IP"
keytool -genkeypair -alias voiceai -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore "$OUT" -storepass "$PASS" \
  -dname "CN=$IP" -ext "SAN=ip:$IP,dns:localhost"

echo
echo "Keystore written to: $OUT"
echo "Run with:"
echo "  TLS_KEYSTORE=$OUT TLS_PASSWORD=$PASS ./scripts/run.sh"
echo "Then open on the phone: https://$IP:8443"
