-- V13__consortium_interlending_schema.sql (generated)
CREATE TABLE format (
    format_id    SERIAL PRIMARY KEY,
    name         VARCHAR(50) NOT NULL
);

CREATE TABLE language (
    language_id  SERIAL PRIMARY KEY,
    code         VARCHAR(10) NOT NULL,
    name         VARCHAR(50) NOT NULL
);

CREATE TABLE consortium_title (
    consortium_title_id  SERIAL PRIMARY KEY,
    isbn                 VARCHAR(20),
    title                VARCHAR(500) NOT NULL,
    format_id            INTEGER REFERENCES format (format_id),
    language_id          INTEGER REFERENCES language (language_id)
);

CREATE TABLE contributor (
    contributor_id  SERIAL PRIMARY KEY,
    full_name       VARCHAR(200) NOT NULL
);

CREATE TABLE title_contributor (
    consortium_title_id  INTEGER REFERENCES consortium_title,
    contributor_id       INTEGER REFERENCES contributor,
    role                 VARCHAR(50),
    PRIMARY KEY (consortium_title_id, contributor_id)
);

CREATE TABLE partner (
    partner_id  SERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL
);

CREATE TABLE interlending_request (
    request_id           SERIAL PRIMARY KEY,
    partner_id           INTEGER REFERENCES partner (partner_id),
    consortium_title_id  INTEGER REFERENCES consortium_title,
    member_id            INTEGER REFERENCES member (id)
                             ON DELETE CASCADE,
    member_name          VARCHAR(200),
    member_email         VARCHAR(200),
    requested_at         TIMESTAMP DEFAULT now(),
    status               VARCHAR(20) DEFAULT 'PENDING'
);

CREATE TABLE interlending_loan (
    interlending_loan_id  SERIAL PRIMARY KEY,
    request_id            INTEGER REFERENCES interlending_request,
    copy_id               INTEGER REFERENCES copy (id),
    shipped_at            TIMESTAMP,
    due_date              TIMESTAMP DEFAULT (now() + INTERVAL '21 days'),
    returned_at           TIMESTAMP
);
