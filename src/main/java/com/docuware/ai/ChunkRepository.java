package com.docuware.ai;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkRepository {
    private final JdbcTemplate jdbc;
    public ChunkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void replace(UUID documentId, List<String> chunks, List<float[]> embeddings) {
        jdbc.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
        for (int i = 0; i < chunks.size(); i++) {
            UUID id = UUID.randomUUID();
            if (embeddings == null) {
                jdbc.update("INSERT INTO document_chunks(id, document_id, chunk_index, content) VALUES (?, ?, ?, ?)", id, documentId, i, chunks.get(i));
            } else {
                jdbc.update("INSERT INTO document_chunks(id, document_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?, CAST(? AS vector))",
                    id, documentId, i, chunks.get(i), vector(embeddings.get(i)));
            }
        }
    }
    public List<Match> search(UUID owner, UUID documentId, String model, float[] embedding, int limit) {
        // Both model and dimensionality are scoped before distance evaluation. Query parameters are bound.
        String sql = """
            WITH eligible AS MATERIALIZED (
                SELECT c.id, c.document_id, c.chunk_index, c.content, c.embedding, d.title
                FROM document_chunks c JOIN documents d ON c.document_id = d.id
                WHERE d.user_id = ? AND d.processing_status = 'COMPLETED'
                  AND d.embedding_model = ? AND c.embedding IS NOT NULL
                  AND vector_dims(c.embedding) = ?
            """ + (documentId == null ? "" : " AND d.id = ? ") + """
            )
            SELECT id, document_id, chunk_index, content, title, 1 - (embedding <=> CAST(? AS vector)) AS score
            FROM eligible ORDER BY embedding <=> CAST(? AS vector), id LIMIT ?
            """;
        List<Object> args = new ArrayList<>(List.of(owner, model, embedding.length));
        if (documentId != null) args.add(documentId);
        args.add(vector(embedding)); args.add(vector(embedding)); args.add(limit);
        return jdbc.query(sql, (rs, row) -> new Match(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
            rs.getInt("chunk_index"), rs.getString("content"), rs.getString("title"), rs.getDouble("score")), args.toArray());
    }
    private String vector(float[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Empty embedding");
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (float value : values) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid embedding"); result.add(Float.toString(value)); }
        return result.toString();
    }
    public record Match(UUID chunkId, UUID documentId, int chunkIndex, String content, String title, double score) {}
}
