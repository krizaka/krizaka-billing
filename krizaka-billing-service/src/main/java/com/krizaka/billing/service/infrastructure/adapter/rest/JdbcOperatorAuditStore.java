package com.krizaka.billing.service.infrastructure.adapter.rest;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOperatorAuditStore implements OperatorAuditStore {

  private final JdbcTemplate jdbc;

  JdbcOperatorAuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(Line line) {
    jdbc.update(
        "INSERT INTO operator_audit (actor_id, actor_label, action, target_id, request_id, outcome)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        line.actorId(),
        line.actorLabel(),
        line.action(),
        line.targetId(),
        line.requestId(),
        line.outcome());
  }

  @Override
  public List<Stored> recent(int limit) {
    return jdbc.query(
        "SELECT id, at, actor_label, action, target_id, request_id, outcome FROM operator_audit"
            + " ORDER BY at DESC, id DESC LIMIT ?",
        (rs, i) ->
            new Stored(
                rs.getLong("id"),
                ((Timestamp) rs.getTimestamp("at")).toInstant(),
                rs.getString("actor_label"),
                rs.getString("action"),
                rs.getString("target_id"),
                rs.getString("request_id"),
                rs.getString("outcome")),
        limit);
  }
}
