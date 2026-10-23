"""
EmbedConsumer — consumes {"eventId": N} messages from the `events.embed` queue,
generates an OpenAI embedding for that market event, and stores it in pgvector.

Pipeline position:
    Java SummarizeConsumer  --(events.embed)-->  this script  -->  market_events.embedding

Delivery semantics: at-least-once. The same eventId may arrive more than once,
so the final write is conditional (WHERE status = 'SUMMARIZED') and therefore
idempotent: a duplicate message results in 0 rows updated and is simply acked.
"""

import json
import logging
import os
import signal
import sys
import time

import pika
import psycopg2
import requests
from dotenv import load_dotenv

load_dotenv()

# ---- Config (names match embedding-script/.env) -------------------------------
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
DB_NAME = os.getenv("DB_NAME")
DB_USER = os.getenv("DB_USER")
DB_HOST = os.getenv("DB_HOST", "localhost")  # same default as embed_events.py
DB_PASSWORD = os.getenv("DB_PASSWORD")  # optional: unset locally

RABBITMQ_HOST = os.getenv("RABBITMQ_HOST", "127.0.0.1")  # IPv4: RabbitMQ doesn't listen on ::1
RABBITMQ_USER = os.getenv("RABBITMQ_USER", "guest")
RABBITMQ_PASSWORD = os.getenv("RABBITMQ_PASSWORD", "guest")

QUEUE = "events.embed"            # declared by the Java app (RabbitConfig)
EMBEDDING_MODEL = "text-embedding-3-small"
MAX_ATTEMPTS = 3                  # mirrors the Java listener retry config
INITIAL_BACKOFF_SECONDS = 2

# Fault injection for local failure drills ONLY: sleep between the DB commit and the ack,
# to simulate a crash in that window. Leave unset in production.
FAULT_DELAY_AFTER_COMMIT = int(os.getenv("FAULT_DELAY_AFTER_COMMIT", "0"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("embed-consumer")
logging.getLogger("pika").setLevel(logging.WARNING)   # pika's connection INFO logs are noise


def build_embedding_text(title, summary):
    # MUST match how embed_events.py builds its text, so all vectors share one space.
    return f"{title or ''}. {summary or ''}".strip()


def get_db_connection():
    # psycopg2 ignores keyword args whose value is None
    return psycopg2.connect(dbname=DB_NAME, user=DB_USER, host=DB_HOST, password=DB_PASSWORD)


def embed(text):
    response = requests.post(
        "https://api.openai.com/v1/embeddings",
        headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
        json={"model": EMBEDDING_MODEL, "input": text},
        timeout=30,
    )
    response.raise_for_status()
    vector = response.json()["data"][0]["embedding"]
    return "[" + ",".join(str(x) for x in vector) + "]"


def process(event_id):
    """Returns a short outcome string. Raises on failures that are worth retrying."""
    with get_db_connection() as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT title, llm_summary, status FROM market_events WHERE id = %s",
            (event_id,),
        )
        row = cur.fetchone()
        if row is None:
            return "not found, dropping"

        title, summary, status = row
        if status == "EMBEDDED":
            return "already embedded, skipping"
        if status != "SUMMARIZED":
            return f"unexpected status {status}, skipping"

        vector = embed(build_embedding_text(title, summary))

        # Conditional write = claim + store in one atomic step (idempotent)
        cur.execute(
            """
            UPDATE market_events
            SET embedding = %s::vector, status = 'EMBEDDED', status_updated_at = now()
            WHERE id = %s AND status = 'SUMMARIZED'
            """,
            (vector, event_id),
        )
        if cur.rowcount == 0:
            return "embedded concurrently by another consumer, skipping"
        # leaving the `with conn` block commits the transaction
        return "embedded"


def on_message(channel, method, properties, body):
    try:
        event_id = int(json.loads(body)["eventId"])
    except (ValueError, KeyError, TypeError):
        log.error("Malformed message %r, sending to DLQ", body)
        channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            outcome = process(event_id)
            log.info("Event %s %s", event_id, outcome)
            if FAULT_DELAY_AFTER_COMMIT:
                log.warning("[fault injection] DB committed, sleeping %ds before ack - kill me now",
                            FAULT_DELAY_AFTER_COMMIT)
                time.sleep(FAULT_DELAY_AFTER_COMMIT)
            # Ack only AFTER the DB commit: a crash before this line means redelivery
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return
        except Exception as exc:
            if attempt < MAX_ATTEMPTS:
                wait = INITIAL_BACKOFF_SECONDS * (2 ** (attempt - 1))
                log.warning("Event %s attempt %d/%d failed: %s (retrying in %ds)",
                            event_id, attempt, MAX_ATTEMPTS, exc, wait)
                time.sleep(wait)
            else:
                log.error("Event %s failed after %d attempts: %s -> DLQ",
                          event_id, MAX_ATTEMPTS, exc)
                # requeue=False -> broker routes it to the dead-letter exchange
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)


def main():
    if not OPENAI_API_KEY or not DB_NAME:
        sys.exit("OPENAI_API_KEY and DB_NAME must be set (see .env)")

    connection = pika.BlockingConnection(
        pika.ConnectionParameters(
            host=RABBITMQ_HOST,
            credentials=pika.PlainCredentials(RABBITMQ_USER, RABBITMQ_PASSWORD),
        )
    )
    channel = connection.channel()

    # Do NOT declare the queue here: the Java app owns its definition (DLX arguments).
    # Re-declaring with different arguments fails with PRECONDITION_FAILED.
    channel.basic_qos(prefetch_count=1)   # take one message at a time
    channel.basic_consume(queue=QUEUE, on_message_callback=on_message, auto_ack=False)

    def shutdown(*_):
        log.info("Shutting down")
        channel.stop_consuming()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)   # systemd stop sends SIGTERM

    log.info("Waiting for messages on %s", QUEUE)
    channel.start_consuming()
    connection.close()


if __name__ == "__main__":
    main()
