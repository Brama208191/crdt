package dev.crdt.editor.document;

import dev.crdt.editor.crdt.Id;
import dev.crdt.editor.crdt.Op;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DocumentRepository {

    record Snapshot(long seq, String state) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    DocumentRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    void create(UUID id, String title) {
        jdbc.sql("insert into documents (id, title) values (?, ?)").params(id, title).update();
    }

    Optional<String> findTitle(UUID id) {
        return jdbc.sql("select title from documents where id = ?").param(id).query(String.class).optional();
    }

    List<Dtos.DocumentSummary> list() {
        return jdbc.sql("select id, title, updated_at from documents order by updated_at desc limit 50")
                .query((rs, i) -> new Dtos.DocumentSummary(
                        rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    long maxSeq(UUID docId) {
        return jdbc.sql("select coalesce(max(seq), 0) from operations where doc_id = ?")
                .param(docId).query(Long.class).single();
    }

    void appendOps(UUID docId, long firstSeq, List<Op> ops) {
        template.batchUpdate(
                "insert into operations (doc_id, seq, kind, counter, site, ref_counter, ref_site, value) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public int getBatchSize() {
                        return ops.size();
                    }

                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setObject(1, docId);
                        ps.setLong(2, firstSeq + i);
                        switch (ops.get(i)) {
                            case Op.Insert ins -> {
                                ps.setString(3, "i");
                                ps.setLong(4, ins.id().counter());
                                ps.setString(5, ins.id().site());
                                ps.setObject(6, ins.after() == null ? null : ins.after().counter());
                                ps.setString(7, ins.after() == null ? null : ins.after().site());
                                ps.setString(8, ins.value());
                            }
                            case Op.Delete del -> {
                                ps.setString(3, "d");
                                ps.setLong(4, del.target().counter());
                                ps.setString(5, del.target().site());
                                ps.setObject(6, null);
                                ps.setString(7, null);
                                ps.setString(8, null);
                            }
                        }
                    }
                });
        jdbc.sql("update documents set updated_at = now() where id = ?").param(docId).update();
    }

    List<Op> opsBetween(UUID docId, long afterSeq, long upToSeq) {
        return jdbc.sql("select * from operations where doc_id = ? and seq > ? and seq <= ? order by seq")
                .params(docId, afterSeq, upToSeq)
                .query((rs, i) -> toOp(rs))
                .list();
    }

    void saveSnapshot(UUID docId, long seq, String state, String content) {
        jdbc.sql("insert into snapshots (doc_id, seq, state, content) values (?, ?, ?, ?) on conflict do nothing")
                .params(docId, seq, state, content).update();
    }

    Optional<Snapshot> latestSnapshotAtOrBefore(UUID docId, long seq) {
        return jdbc.sql("select seq, state from snapshots where doc_id = ? and seq <= ? order by seq desc limit 1")
                .params(docId, seq)
                .query((rs, i) -> new Snapshot(rs.getLong("seq"), rs.getString("state")))
                .optional();
    }

    List<Dtos.Version> versions(UUID docId) {
        return jdbc.sql("select seq, created_at, left(content, 120) as preview from snapshots "
                        + "where doc_id = ? order by seq desc")
                .param(docId)
                .query((rs, i) -> new Dtos.Version(
                        rs.getLong("seq"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("preview")))
                .list();
    }

    private static Op toOp(ResultSet rs) throws SQLException {
        Id id = new Id(rs.getLong("counter"), rs.getString("site"));
        if (rs.getString("kind").equals("d")) {
            return new Op.Delete(id);
        }
        String refSite = rs.getString("ref_site");
        Id after = refSite == null ? null : new Id(rs.getLong("ref_counter"), refSite);
        return new Op.Insert(id, after, rs.getString("value"));
    }
}
