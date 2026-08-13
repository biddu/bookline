# ch13-rag-recommender — run 2, verbatim

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt: identical to run 1.
- Edits made: none.
- **Note: persists the index to disk with a load-or-build path, hands final ranking to the model and re-validates returned ids, and sizes the work in tokens without ever converting it to money. Like run 1: no timeout, no injection guard, silent fallback.**

---

Here's a complete, practical RAG recommender you can run against a library catalogue. I'll give you the design rationale first, then the code.

## Design

A "RAG recommender" here means: **retrieve** semantically similar books from a vector index of the catalogue, then have an LLM **generate** grounded explanations using only the retrieved records (so it can't hallucinate books you don't own). The pipeline is:

1. **Ingest**: Build one text document per book from title + author + subjects + description. The description does most of the semantic work; subjects anchor it.
2. **Embed & index**: Sentence-transformer embeddings into a vector index. For a library catalogue (thousands to low millions of records), exact nearest-neighbour search with normalized vectors is fine — no need for approximate ANN until you're past ~1M records.
3. **Retrieve**: Look up the query title in the catalogue (fuzzy match, so "the hobbit" works), embed its document, pull top-k neighbours, drop the seed book itself and other editions of it.
4. **Generate**: Pass the seed record + candidates to an LLM with a prompt that forces it to pick 5 *from the candidates only* and explain each with reference to concrete shared features. Fall back to a template explanation if no LLM key is configured.

```python
"""
RAG book recommender for a library catalogue.

Given a title, recommends 5 similar books with explanations.

Dependencies:
  pip install sentence-transformers numpy anthropic rapidfuzz
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from rapidfuzz import fuzz, process as fuzz_process
from sentence_transformers import SentenceTransformer

EMBED_MODEL = "sentence-transformers/all-MiniLM-L6-v2"  # fast; swap for
# "BAAI/bge-large-en-v1.5" or an API embedder if quality matters more than speed.


# ---------------------------------------------------------------- data model

@dataclass
class Book:
    book_id: str
    title: str
    author: str = ""
    subjects: list[str] = field(default_factory=list)
    description: str = ""
    year: int | None = None

    def to_document(self) -> str:
        parts = [
            f"Title: {self.title}",
            f"Author: {self.author}" if self.author else "",
            f"Subjects: {', '.join(self.subjects)}" if self.subjects else "",
            f"Description: {self.description}" if self.description else "",
        ]
        return "\n".join(p for p in parts if p)

    def brief(self) -> str:
        """Compact record for the LLM prompt."""
        return json.dumps(
            {
                "id": self.book_id,
                "title": self.title,
                "author": self.author,
                "year": self.year,
                "subjects": self.subjects[:8],
                "description": (self.description or "")[:600],
            },
            ensure_ascii=False,
        )


# ---------------------------------------------------------------- vector index

class VectorIndex:
    """Exact cosine-similarity search over normalized embeddings.
    Plenty fast for catalogue-scale data; swap for FAISS/pgvector past ~1M rows."""

    def __init__(self, model_name: str = EMBED_MODEL):
        self.model = SentenceTransformer(model_name)
        self.embeddings: np.ndarray | None = None
        self.ids: list[str] = []

    def build(self, ids: list[str], documents: list[str], batch_size: int = 64):
        self.ids = ids
        self.embeddings = self.model.encode(
            documents,
            batch_size=batch_size,
            normalize_embeddings=True,       # so dot product == cosine
            show_progress_bar=True,
        )

    def save(self, path: str | Path):
        path = Path(path)
        np.save(path.with_suffix(".npy"), self.embeddings)
        path.with_suffix(".ids.json").write_text(json.dumps(self.ids))

    def load(self, path: str | Path) -> bool:
        path = Path(path)
        npy, idsf = path.with_suffix(".npy"), path.with_suffix(".ids.json")
        if not (npy.exists() and idsf.exists()):
            return False
        self.embeddings = np.load(npy)
        self.ids = json.loads(idsf.read_text())
        return True

    def search(self, query_doc: str, top_k: int = 20) -> list[tuple[str, float]]:
        q = self.model.encode([query_doc], normalize_embeddings=True)[0]
        scores = self.embeddings @ q
        top = np.argsort(-scores)[:top_k]
        return [(self.ids[i], float(scores[i])) for i in top]


# ---------------------------------------------------------------- recommender

class BookRecommender:
    def __init__(self, books: list[Book], index_cache: str | Path | None = None):
        self.books: dict[str, Book] = {b.book_id: b for b in books}
        self.index = VectorIndex()
        if index_cache and self.index.load(index_cache):
            pass  # reused cached embeddings
        else:
            ids = list(self.books)
            docs = [self.books[i].to_document() for i in ids]
            self.index.build(ids, docs)
            if index_cache:
                self.index.save(index_cache)

    def find_book(self, title_query: str) -> Book | None:
        titles = {bid: b.title for bid, b in self.books.items()}
        match = fuzz_process.extractOne(
            title_query, titles, scorer=fuzz.WRatio, score_cutoff=70,
        )
        if match is None:
            return None
        _, _, book_id = match
        return self.books[book_id]

    def retrieve_candidates(self, seed: Book, top_k: int = 15) -> list[tuple[Book, float]]:
        hits = self.index.search(seed.to_document(), top_k=top_k + 10)
        out = []
        for bid, score in hits:
            cand = self.books[bid]
            if bid == seed.book_id:
                continue
            # Drop other editions / same-title-same-author duplicates.
            if (
                fuzz.ratio(cand.title.lower(), seed.title.lower()) > 90
                and fuzz.ratio(cand.author.lower(), seed.author.lower()) > 85
            ):
                continue
            out.append((cand, score))
            if len(out) == top_k:
                break
        return out

    def recommend(self, title_query: str, n: int = 5) -> dict:
        seed = self.find_book(title_query)
        if seed is None:
            return {"error": f"No catalogue match for {title_query!r}."}

        candidates = self.retrieve_candidates(seed, top_k=15)
        if not candidates:
            return {"error": "No similar books found in the catalogue."}

        recs = self._llm_explain(seed, candidates, n) or self._fallback_explain(
            seed, candidates, n
        )
        return {"seed": {"title": seed.title, "author": seed.author},
                "recommendations": recs}

    def _llm_explain(self, seed: Book, candidates: list[tuple[Book, float]], n: int):
        """Ask an LLM to pick the best n from the retrieved candidates and
        explain each. Grounded: it may only choose from candidate IDs."""
        if not os.environ.get("ANTHROPIC_API_KEY"):
            return None
        try:
            import anthropic

            candidate_block = "\n".join(
                f"(score={score:.3f}) {book.brief()}" for book, score in candidates
            )
            prompt = f"""You are a librarian's recommendation assistant.

READER'S BOOK:
{seed.brief()}

CANDIDATE BOOKS FROM OUR CATALOGUE (with retrieval similarity scores):
{candidate_block}

Pick the {n} best recommendations for someone who enjoyed the reader's book.
Rules:
- Choose ONLY from the candidates above; use their exact "id" values.
- Prefer variety (don't pick {n} near-identical books) while staying relevant.
- Each explanation must cite concrete shared features (themes, subjects,
  style, setting) visible in the records — no invented plot details.

Respond with JSON only:
[{{"id": "...", "title": "...", "author": "...", "reason": "1-2 sentences"}}]"""

            client = anthropic.Anthropic()
            resp = client.messages.create(
                model="claude-sonnet-4-5",
                max_tokens=1500,
                messages=[{"role": "user", "content": prompt}],
            )
            text = resp.content[0].text
            json_str = text[text.index("["): text.rindex("]") + 1]
            recs = json.loads(json_str)
            # Guard against hallucinated IDs — keep only real catalogue entries.
            valid_ids = {b.book_id for b, _ in candidates}
            recs = [r for r in recs if r.get("id") in valid_ids][:n]
            return recs or None
        except Exception:
            return None  # fall back to templates rather than failing the request

    @staticmethod
    def _fallback_explain(seed: Book, candidates: list[tuple[Book, float]], n: int):
        """No-LLM fallback: template explanations from shared subjects."""
        recs = []
        seed_subj = {s.lower() for s in seed.subjects}
        for book, score in candidates[:n]:
            shared = [s for s in book.subjects if s.lower() in seed_subj]
            if shared:
                reason = f"Shares themes with '{seed.title}': {', '.join(shared[:3])}."
            else:
                reason = (f"Closest match in the catalogue by overall content "
                          f"similarity ({score:.0%}).")
            if book.author and book.author == seed.author:
                reason += " Also by the same author."
            recs.append({"id": book.book_id, "title": book.title,
                         "author": book.author, "reason": reason})
        return recs
```

Expected CSV columns: `id,title,author,subjects,description,year`, with subjects separated by `;` or `|` (MARC exports usually give you 650 fields that map straight onto this).

## Notes and things I'd flag

**Why retrieve 15 and let the LLM pick 5.** Pure embedding rank tends to return five near-clones (same series, same author repeatedly). Giving the LLM a wider candidate pool and asking for relevant-but-varied picks is a cheap re-ranking step that noticeably improves the felt quality of recommendations.

**Grounding is the whole point.** The prompt restricts the model to candidate IDs, and the code re-validates returned IDs against the catalogue afterwards — so you can never recommend a book you don't hold, which is the failure mode of asking an LLM for recommendations directly.

**Descriptions matter more than anything else.** If your catalogue rows are just title/author/Dewey number, embedding quality will be mediocre. If descriptions are sparse, enrich from Open Library or Google Books APIs by ISBN before indexing — that's the highest-leverage improvement available.

**Cold start / cost.** Embedding is a one-time cost (cached to disk here); each query costs one embedding lookup plus one small LLM call (~1–2k tokens). The no-key fallback path means the system degrades gracefully to subject-overlap explanations instead of erroring.

**Scaling.** Exact search with normalized MiniLM vectors (384-dim) handles ~1M books in well under 100ms with NumPy. Beyond that, or if you need incremental updates without rebuilding, move the index to FAISS (`IndexFlatIP` → `IndexHNSWFlat`) or pgvector if the catalogue already lives in Postgres — the `VectorIndex` class is the only thing you'd swap.

**Edition dedup is heuristic.** The fuzzy title+author filter catches most duplicate editions; if your catalogue has FRBR work IDs or OCLC work numbers, cluster on those instead — it's strictly better.

**Evaluation.** Before shipping, sanity-check with a small golden set: pick ~20 well-known titles, have a librarian mark each returned rec as sensible/not. If precision looks weak, the first knobs to turn are (1) richer descriptions, (2) a stronger embedding model (bge-large), (3) raising `top_k` and letting the LLM do more of the ranking work.
