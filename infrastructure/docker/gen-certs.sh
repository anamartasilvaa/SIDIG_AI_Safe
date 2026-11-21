#!/usr/bin/env bash

# =====================================================================
#  AISafe - certificados e segredos para a stack Docker segura
#  Uso, a partir de infrastructure/docker:
#    ./gen-certs.sh
#    ./gen-certs.sh --force
# =====================================================================

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$SCRIPT_DIR/certs"
ENV_FILE="$INFRA_DIR/.env"
ENV_EXAMPLE="$INFRA_DIR/.env.example"
FORCE=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--force] [--out-dir DIRECTORY]

Options:
  --force                 Regenerate existing certificates.
  --out-dir DIRECTORY    Certificate output directory.
EOF
}

while (($#)); do
    case "$1" in
        --force|-f)
            FORCE=true
            shift
            ;;
        --out-dir)
            (($# >= 2)) || { echo "--out-dir requires a directory." >&2; exit 2; }
            OUT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -f "$OUT_DIR/aisafe.pem" && "$FORCE" != true ]]; then
    echo "Os certificados ja existem em $OUT_DIR (use --force para os regenerar)."
    exit 0
fi

KEYTOOL="keytool"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
fi
command -v "$KEYTOOL" >/dev/null 2>&1 || {
    echo "keytool nao encontrado (instale um JDK ou defina JAVA_HOME)." >&2
    exit 1
}
command -v openssl >/dev/null 2>&1 || {
    echo "openssl nao encontrado." >&2
    exit 1
}
[[ -f "$ENV_EXAMPLE" ]] || {
    echo "Ficheiro .env.example nao encontrado: $ENV_EXAMPLE" >&2
    exit 1
}

new_secret() {
    local length="$1"
    local chars='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    local result=''
    local byte

    while ((${#result} < length)); do
        while IFS= read -r -n1 byte; do
            result+="${chars[$(( $(printf '%d' "'$byte") % ${#chars} ))]}"
            ((${#result} >= length)) && break
        done < <(od -An -N256 -tu1 /dev/urandom | tr -s ' ' '\n')
    done
    printf '%s' "$result"
}

if [[ ! -f "$ENV_FILE" ]]; then
    cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

declare -A values=()
env_tmp="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
trap 'rm -f "$env_tmp"' EXIT

while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*([A-Z_]+)=(.*)$ ]]; then
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        if [[ "$value" == CHANGE_ME ]]; then
            length=24
            [[ "$key" == JWT_SECRET ]] && length=48
            value="$(new_secret "$length")"
            line="$key=$value"
            echo "[1/3] $key gerado em $ENV_FILE"
        fi
        values["$key"]="$value"
    fi
    printf '%s\n' "$line" >> "$env_tmp"
done < "$ENV_FILE"

mv "$env_tmp" "$ENV_FILE"
trap - EXIT

password="${values[KEYSTORE_PASSWORD]:-}"
[[ -n "$password" ]] || {
    echo "KEYSTORE_PASSWORD nao definido no .env" >&2
    exit 1
}

mkdir -p "$OUT_DIR"
find "$OUT_DIR" -mindepth 1 -maxdepth 1 -type f -delete

san='SAN=dns:localhost,dns:gateway,dns:keycloak,dns:aircraft-1,dns:aircraft-2,dns:maintenance-1,dns:maintenance-2,dns:airports-routes-1,dns:airports-routes-2,dns:flight-operations-1,dns:flight-operations-2,dns:rabbitmq-1,dns:rabbitmq-2,dns:rabbitmq-3,ip:127.0.0.1'

"$KEYTOOL" -genkeypair \
    -alias aisafe-key \
    -keyalg RSA \
    -keysize 2048 \
    -validity 365 \
    -storetype PKCS12 \
    -keystore "$OUT_DIR/keystore.p12" \
    -storepass "$password" \
    -dname 'CN=localhost, O=AISafe, C=PT' \
    -ext "$san"

"$KEYTOOL" -exportcert \
    -alias aisafe-key \
    -keystore "$OUT_DIR/keystore.p12" \
    -storepass "$password" \
    -rfc \
    -file "$OUT_DIR/aisafe.pem"

"$KEYTOOL" -importcert \
    -alias aisafe \
    -file "$OUT_DIR/aisafe.pem" \
    -keystore "$OUT_DIR/truststore.p12" \
    -storetype PKCS12 \
    -storepass "$password" \
    -noprompt

echo "[2/3] keystore.p12, truststore.p12 e aisafe.pem criados em $OUT_DIR"

openssl pkcs12 \
    -in "$OUT_DIR/keystore.p12" \
    -nocerts \
    -nodes \
    -passin "pass:$password" \
    -out "$OUT_DIR/aisafe.key"

echo "[3/3] aisafe.key criada."
echo "Pronto: docker compose -f ../docker-compose.secure.yml up --build"
echo "(Em Linux/macOS, os ficheiros em certs/ precisam de ser legiveis pelos containers: chmod 644.)"
