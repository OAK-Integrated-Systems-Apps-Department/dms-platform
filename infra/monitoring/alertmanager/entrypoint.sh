#!/bin/sh
# Renders alertmanager.yml.tmpl from the environment, then runs Alertmanager.
#
# Run as `/bin/sh entrypoint.sh` (compose does), so the file needs no execute
# bit - one less thing to be lost on a Windows checkout.
set -eu

HOST="${ALERT_SMTP_HOST:-smtp.gmail.com}"
PORT="${ALERT_SMTP_PORT:-587}"
FROM="${ALERT_SMTP_FROM:-alerts@oakdms.local}"
USERNAME="${ALERT_SMTP_USERNAME:-}"
STARTTLS="${ALERT_SMTP_STARTTLS:-true}"
TO="${ALERT_EMAIL_TO:-}"

# Only a boolean may reach the config: this value lands unquoted in YAML.
case "$STARTTLS" in
  true|false) ;;
  *) echo "ALERT_SMTP_STARTTLS must be true or false, got '$STARTTLS'" >&2; exit 1 ;;
esac

LINK="${ALERT_LINK:-}"
# The link is optional, and only ever an http(s) URL.
case "$LINK" in
  ""|http://*|https://*) ;;
  *) echo "ALERT_LINK must start with http:// or https://, got '$LINK'" >&2; exit 1 ;;
esac
sed -e "s~__LINK__~${LINK}~" /etc/alertmanager/email.tmpl.in > /tmp/email.tmpl

# The password goes through printf, not sed: a password may hold any
# character, and one of them would break a substitution.
printf '%s' "${ALERT_SMTP_PASSWORD:-}" > /tmp/smtp_password

# `~` as the sed delimiter - it cannot appear in a host name or an address.
sed \
  -e "s~__SMARTHOST__~${HOST}:${PORT}~" \
  -e "s~__FROM__~${FROM}~" \
  -e "s~__USERNAME__~${USERNAME}~" \
  -e "s~__STARTTLS__~${STARTTLS}~" \
  /etc/alertmanager/alertmanager.yml.tmpl > /tmp/alertmanager.yml

if [ -n "$TO" ]; then
  # `to` takes a comma-separated list, so several recipients need nothing
  # more than "a@x.com,b@x.com" in ALERT_EMAIL_TO.
  cat >> /tmp/alertmanager.yml <<EOF
    email_configs:
      - to: "${TO}"
        # So the message that the problem is over arrives too - without it a
        # firing email is followed by silence, and nobody knows it recovered.
        send_resolved: true
        # Plain text only: setting html to empty stops the stock HTML body,
        # which is the one carrying the unreachable container links.
        headers:
          Subject: '{{ template "oak.subject" . }}'
        text: '{{ template "oak.text" . }}'
        html: ''
EOF
  echo "alertmanager: email to ${TO} via ${HOST}:${PORT}"
else
  echo "alertmanager: ALERT_EMAIL_TO is empty - alerts are grouped and visible, but nothing is sent"
fi

exec /bin/alertmanager \
  --config.file=/tmp/alertmanager.yml \
  --storage.path=/alertmanager \
  --web.listen-address=:9093
