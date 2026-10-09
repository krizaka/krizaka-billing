package com.krizaka.billing.service.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Snapshots every commercial mutation into {@code billing_version_history}.
 *
 * <p>A price change is a legal artefact, not a config tweak: six months later "why was this user
 * charged that?" must be answerable, and "who decided this?" must have a name attached. Each row
 * carries the full prior state, its SHA-256, and the admin who changed it.
 *
 * <p>This is also what makes the design's incident argument real. Putting the enforcement mode in
 * the database is only defensible if reverting it is one click, and one-click revert needs a
 * previous snapshot to restore — without this table the lever is one-way.
 */
@Service
public class BillingVersionHistoryService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public BillingVersionHistoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
  }

  /**
   * Records the state of an entity before it changed. Call inside the mutating transaction.
   *
   * @param entityType {@code PLAN}, {@code PACKAGE}, {@code PRICEBOOK} or {@code CONFIG}
   * @param entityKey the entity's key
   * @param snapshot the prior state, serialised to JSONB
   * @param changedBy the admin's actor id — never {@code system} for an admin-initiated change
   */
  public void snapshot(String entityType, String entityKey, Object snapshot, String changedBy) {
    String payload = objectMapper.writeValueAsString(snapshot);
    jdbcTemplate.update(
        "INSERT INTO billing_version_history (entity_type, entity_key, snapshot, sha256_hash,"
            + " created_by) VALUES (?, ?, ?::jsonb, ?, ?)",
        entityType,
        entityKey,
        payload,
        sha256(payload),
        changedBy);
  }

  /**
   * The change history of one entity, newest first — the backing query for a diff-and-rollback
   * view.
   *
   * @param entityType the entity type
   * @param entityKey the entity's key
   * @return the recorded snapshots as raw JSON, newest first
   */
  public List<String> history(String entityType, String entityKey) {
    return jdbcTemplate.query(
        "SELECT snapshot::text FROM billing_version_history"
            + " WHERE entity_type = ? AND entity_key = ? ORDER BY created_at DESC",
        (rs, rowNum) -> rs.getString(1),
        entityType,
        entityKey);
  }

  /** Dependency-free pure function over the serialised payload — a sanctioned static (ERR-127b). */
  private static String sha256(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK and must be present", e);
    }
  }
}
