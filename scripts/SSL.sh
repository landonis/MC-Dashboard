#!/bin/bash
# Script: switch_to_lets_encrypt.sh
# Purpose: Replace self-signed certs with Let's Encrypt in Nginx config

set -e
DOMAIN=""
LE_CERT_PATH="/etc/letsencrypt/live/$DOMAIN"
NGINX_CONFIG="/etc/nginx/sites-enabled/dashboard"  # Adjust if you're using a different path

echo "[*] Checking Let's Encrypt certificate path..."

if [ ! -f "$LE_CERT_PATH/fullchain.pem" ]; then
    echo "[!] Certificate for $DOMAIN not found at $LE_CERT_PATH"
    echo "Did you run: sudo certbot certonly --nginx -d $DOMAIN ?"
    exit 1
fi

echo "[*] Updating Nginx config at $NGINX_CONFIG..."

# Backup
cp "$NGINX_CONFIG" "$NGINX_CONFIG.bak"

# Replace certificate lines
sed -i "s|ssl_certificate .*|ssl_certificate $LE_CERT_PATH/fullchain.pem;|" "$NGINX_CONFIG"
sed -i "s|ssl_certificate_key .*|ssl_certificate_key $LE_CERT_PATH/privkey.pem;|" "$NGINX_CONFIG"

# Replace server_name
sed -i "s|server_name .*;|server_name $DOMAIN;|" "$NGINX_CONFIG"

echo "[*] Testing Nginx config..."
nginx -t

echo "[*] Reloading Nginx..."
systemctl reload nginx

echo "[✓] Nginx is now using Let's Encrypt SSL for $DOMAIN"
