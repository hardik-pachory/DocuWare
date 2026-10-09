CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    content_type VARCHAR(150) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    file_type VARCHAR(10) NOT NULL,
    processing_status VARCHAR(20) NOT NULL,
    processing_token UUID,
    processing_error VARCHAR(200),
    extracted_text TEXT,
    summary TEXT,
    summary_model VARCHAR(100),
    embedding_model VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX documents_owner_created ON documents(user_id, created_at DESC);
CREATE INDEX documents_processing ON documents(processing_status, updated_at);
CREATE TABLE document_tags (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag VARCHAR(50) NOT NULL,
    PRIMARY KEY(document_id, tag)
);
CREATE INDEX tags_lookup ON document_tags(tag);
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding vector,
    UNIQUE(document_id, chunk_index)
);
CREATE INDEX chunks_document ON document_chunks(document_id);
