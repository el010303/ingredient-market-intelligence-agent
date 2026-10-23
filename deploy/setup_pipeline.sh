#!/usr/bin/env bash
# Provision the async ingestion pipeline on the EC2 host (Ubuntu 24.04).
# Run on the server from ~/app after uploading:
#   app.jar.new, embed_consumer.py, 2026-10-22_pipeline_status.sql, setup_pipeline.sh
# Safe to re-run: every step checks whether it has already been done.
set -euo pipefail

APP_DIR=/home/ubuntu/app
ENV_FILE=$APP_DIR/app.env
CONSUMER_DIR=$APP_DIR/embedding
CONSUMER_ENV=$APP_DIR/consumer.env

get() { grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

echo "== 1/8 Install RabbitMQ and python3-venv"
sudo apt-get update -qq
sudo apt-get install -y -qq rabbitmq-server python3-venv
sudo systemctl enable --now rabbitmq-server

echo "== 2/8 Dedicated RabbitMQ user (password generated here, never printed)"
if ! grep -q '^SPRING_RABBITMQ_PASSWORD=' "$ENV_FILE"; then
  RMQ_PASS=$(openssl rand -hex 24)
  sudo rabbitmqctl add_user market_intel "$RMQ_PASS" >/dev/null
  sudo rabbitmqctl set_permissions -p / market_intel ".*" ".*" ".*" >/dev/null
  printf 'SPRING_RABBITMQ_USERNAME=market_intel\nSPRING_RABBITMQ_PASSWORD=%s\n' "$RMQ_PASS" >> "$ENV_FILE"
  unset RMQ_PASS
fi
sudo rabbitmqctl delete_user guest >/dev/null 2>&1 || true

echo "== 3/8 Enable scheduled ingestion in production"
grep -q '^APP_INGESTION_ENABLED=' "$ENV_FILE" || echo 'APP_INGESTION_ENABLED=true' >> "$ENV_FILE"
chmod 600 "$ENV_FILE"

echo "== 4/8 Consumer environment file (derived from app.env)"
DB_URL=$(get SPRING_DATASOURCE_URL)
DB_NAME=${DB_URL##*/}
umask 077
cat > "$CONSUMER_ENV" <<ENV
OPENAI_API_KEY=$(get OPENAI_API_KEY)
DB_NAME=$DB_NAME
DB_USER=$(get SPRING_DATASOURCE_USERNAME)
DB_PASSWORD=$(get SPRING_DATASOURCE_PASSWORD)
DB_HOST=localhost
RABBITMQ_HOST=127.0.0.1
RABBITMQ_USER=$(get SPRING_RABBITMQ_USERNAME)
RABBITMQ_PASSWORD=$(get SPRING_RABBITMQ_PASSWORD)
ENV
umask 022

echo "== 5/8 Python virtualenv for the embed consumer"
mkdir -p "$CONSUMER_DIR"
cp "$APP_DIR/embed_consumer.py" "$CONSUMER_DIR/"
[ -d "$CONSUMER_DIR/.venv" ] || python3 -m venv "$CONSUMER_DIR/.venv"
"$CONSUMER_DIR/.venv/bin/pip" install -q pika psycopg2-binary requests python-dotenv

echo "== 6/8 Database migration (only if the status column is missing)"
HAS_STATUS=$(sudo -u postgres psql -d "$DB_NAME" -tAc \
  "SELECT 1 FROM information_schema.columns WHERE table_name='market_events' AND column_name='status'")
if [ "$HAS_STATUS" != "1" ]; then
  # Fed through stdin: the postgres user cannot read files under /home/ubuntu
  sudo -u postgres psql -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$APP_DIR/2026-10-22_pipeline_status.sql"
else
  echo "   status column already present, skipping"
fi

echo "== 7/8 systemd units"
sudo tee /etc/systemd/system/market-intel-embed.service >/dev/null <<UNIT
[Unit]
Description=Market Intelligence embed consumer (events.embed -> pgvector)
After=network.target rabbitmq-server.service postgresql.service market-intel.service
Requires=rabbitmq-server.service

[Service]
User=ubuntu
WorkingDirectory=$CONSUMER_DIR
EnvironmentFile=$CONSUMER_ENV
ExecStart=$CONSUMER_DIR/.venv/bin/python -u embed_consumer.py
# Exits if the broker connection drops or the queue isn't declared yet; systemd brings it back
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT
# The API should also start after the broker
sudo sed -i 's/^After=network.target postgresql.service$/After=network.target postgresql.service rabbitmq-server.service/' \
  /etc/systemd/system/market-intel.service
sudo systemctl daemon-reload

echo "== 8/8 Swap in the new JAR and (re)start services"
if [ -f "$APP_DIR/app.jar.new" ]; then
  sudo systemctl stop market-intel
  mv "$APP_DIR/app.jar" "$APP_DIR/app.jar.bak"
  mv "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"
fi
sudo systemctl restart market-intel
sudo systemctl enable market-intel-embed >/dev/null 2>&1
echo "   waiting 30s for Spring Boot to declare the queues..."
sleep 30
sudo systemctl restart market-intel-embed
sleep 5

echo
echo "== Verification"
echo "market-intel:       $(systemctl is-active market-intel)"
echo "market-intel-embed: $(systemctl is-active market-intel-embed)"
sudo rabbitmqctl list_queues name messages consumers 2>/dev/null
sudo -u postgres psql -d "$DB_NAME" -tAc "SELECT status, count(*) FROM market_events GROUP BY status"
free -h | head -2
