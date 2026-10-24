#!/usr/bin/env bash
set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/certs"
mkdir -p "${CERT_DIR}"

TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-securepassword123}"

echo "==> Generating Root CA private key and certificate..."
openssl genrsa -out "${CERT_DIR}/ca.key" 4096
openssl req -x509 -new -nodes -sha256 -key "${CERT_DIR}/ca.key" \
    -days 3650 -subj "/CN=BloomFilter-Root-CA/O=CodingDevs" \
    -out "${CERT_DIR}/ca.crt"

echo "==> Generating Redis Stack Server private key and CSR..."
openssl genrsa -out "${CERT_DIR}/redis.key" 2048
openssl req -new -key "${CERT_DIR}/redis.key" \
    -subj "/CN=redis-bloom-secure/O=CodingDevs" \
    -out "${CERT_DIR}/redis.csr"

cat > "${CERT_DIR}/redis.ext" <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = redis-bloom
DNS.3 = redis-bloom-secure
DNS.4 = redis-bloom-secure.staging-environment.svc.cluster.local
DNS.5 = redis-bloom-secure.bloom-system.svc.cluster.local
IP.1 = 127.0.0.1
EOF

echo "==> Signing Redis Server certificate with Root CA..."
openssl x509 -req -in "${CERT_DIR}/redis.csr" \
    -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" -CAcreateserial \
    -out "${CERT_DIR}/redis.crt" -days 365 -sha256 \
    -extfile "${CERT_DIR}/redis.ext"

echo "==> Generating PKCS12 Truststore for Java / Spring Boot nodes..."
rm -f "${CERT_DIR}/truststore.p12"
keytool -importcert -noprompt \
    -alias bloom-ca \
    -file "${CERT_DIR}/ca.crt" \
    -keystore "${CERT_DIR}/truststore.p12" \
    -storetype PKCS12 \
    -storepass "${TRUSTSTORE_PASSWORD}"

rm -f "${CERT_DIR}/redis.csr" "${CERT_DIR}/redis.ext"
echo "==> Certificates and truststore generated in ${CERT_DIR}"
