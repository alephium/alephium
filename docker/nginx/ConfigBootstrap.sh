#!/bin/sh
echo "Running Config Script"
#requierement
apk add apache2-utils openssl
chmod +x /etc/ssl/certs/GenerateCertificate.sh
chown root:root /etc/ssl/certs/GenerateCertificate.sh 
#htpasswd
HTPASSWD=/etc/nginx/.htpasswd
if test ! -s "$HTPASSWD"; then
    htpasswd -db /etc/nginx/.htpasswd ${AUTH_LOGIN} ${AUTH_PASSWORD}
    echo "New htpasswd generated"
fi

#certificate
CERTIFICATE=/etc/ssl/certs/alephium-selfsigned.crt
if test ! -s "$CERTIFICATE"; then
    cd /etc/ssl/certs/ && ./GenerateCertificate.sh alephium-selfsigned
    echo "New SSL certificate Generated"
fi