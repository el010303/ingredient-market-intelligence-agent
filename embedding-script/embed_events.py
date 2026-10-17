import os
import requests
import psycopg2
from dotenv import load_dotenv

load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
DB_NAME = os.getenv("DB_NAME")
DB_USER = os.getenv("DB_USER")

EMBEDDING_URL = "https://api.openai.com/v1/embeddings"
MODEL = "text-embedding-3-small"


def get_embedding(text):
    response = requests.post(
        EMBEDDING_URL,
        headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
        json={"model": MODEL, "input": text},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()["data"][0]["embedding"]


def main():
    conn = psycopg2.connect(dbname=DB_NAME, user=DB_USER, host="localhost")
    cur = conn.cursor()

    cur.execute(
        "SELECT id, title, llm_summary FROM market_events WHERE embedding IS NULL"
    )
    rows = cur.fetchall()
    print(f"Found {len(rows)} events without embeddings")

    done = 0
    for event_id, title, summary in rows:
        text = f"{title or ''}. {summary or ''}".strip()
        if not text or text == ".":
            print(f"Skipping id={event_id}: no text")
            continue
        try:
            vector = get_embedding(text)
            vector_str = "[" + ",".join(str(x) for x in vector) + "]"
            cur.execute(
                "UPDATE market_events SET embedding = %s::vector WHERE id = %s",
                (vector_str, event_id),
            )
            conn.commit()
            done += 1
            print(f"Embedded id={event_id}")
        except Exception as e:
            conn.rollback()
            print(f"Failed id={event_id}: {e}")

    print(f"Done. {done} embedded.")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()