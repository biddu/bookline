# recommender.py (generated)

def recommend(member_id, title_id):
    # Load the catalogue and embed it for similarity search
    titles = db.query(
        "SELECT id, title, author, subjects, description FROM title")
    embeddings = []
    for t in titles:
        embeddings.append(embed(record_text(t)))   # one API call per title

    seed_vector = embed(record_text(db.get_title(title_id)))

    ranked = sorted(zip(titles, embeddings),
                    key=lambda te: cosine(seed_vector, te[1]),
                    reverse=True)
    candidates = ranked[1:6]

    history = db.query(
        "SELECT * FROM loan WHERE member_id = %s", member_id)

    prompt = (
        "You are a friendly librarian. The member's borrowing history: "
        f"{history}. Recommend these books: {candidates}. "
        "For each one, explain warmly why they will love it."
    )
    return complete(prompt)
