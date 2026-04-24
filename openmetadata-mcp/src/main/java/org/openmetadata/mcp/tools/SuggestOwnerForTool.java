package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.ChangeEventRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Stewardship Copilot — suggests owners for a specific entity using weighted candidate ranking.
 *
 * <p>Candidates are scored using tunable weights (per spec R5.3):
 * <ul>
 *   <li>3× most frequent recent patcher of this entity (from ChangeEvents, last 90 days)</li>
 *   <li>2× owners of immediate upstream entities</li>
 *   <li>1× domain default owner</li>
 *   <li>1× owners of sibling tables in same schema</li>
 * </ul>
 *
 * <p>Returns top-3 candidates with rationale strings.
 *
 * <p>Spec reference: Expansions Group E5 (R5.1, R5.3).
 */
@Slf4j
public class SuggestOwnerForTool implements McpTool {

  // Candidate weights per R5.3
  private static final double WEIGHT_RECENT_PATCHER = 3.0;
  private static final double WEIGHT_UPSTREAM_OWNER = 2.0;
  private static final double WEIGHT_DOMAIN_DEFAULT = 1.0;
  private static final double WEIGHT_SIBLING_OWNER = 1.0;

  private static final int LOOKBACK_DAYS = 90;
  private static final int MAX_CANDIDATES = 3;
  private static final int MAX_UPSTREAM_HOPS = 1;

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {
    return execute(
        params,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultChangeEventRepositoryProvider(),
        McpEntityBridge.defaultLineageRepositoryProvider(),
        McpEntityBridge.defaultEntityByReferenceFetcher(),
        McpEntityBridge.defaultEntityFetcher());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity}
   * static method calls and authorizer delegation, eliminating the need for {@code
   * mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider,
      McpEntityBridge.LineageRepositoryProvider lineageProvider,
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher,
      McpEntityBridge.EntityFetcher entityFetcher)
      throws IOException {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    LOG.info("Suggesting owner for: {}/{}", entityType, fqn);

    // Step 1: Collect candidates with scores
    Map<String, OwnerCandidate> candidateMap = new LinkedHashMap<>();

    // 1a: Recent patchers (3× weight)
    addRecentPatchers(candidateMap, entityRef, fqn, entityType, changeEventRepoProvider);

    // 1b: Owners of upstream entities (2× weight)
    addUpstreamOwners(candidateMap, fqn, entityType, lineageProvider, entityFetcher);

    // 1c: Domain default owner (1× weight)
    addDomainDefaultOwner(
        candidateMap, entityRef, fqn, entityType, entityByRefFetcher, entityFetcher);

    // 1d: Owners of sibling tables in same schema (1× weight)
    addSiblingOwners(candidateMap, fqn, entityType, entityFetcher);

    // Step 2: Sort by score, take top-3
    List<OwnerCandidate> ranked =
        candidateMap.values().stream()
            .sorted(Comparator.comparingDouble(OwnerCandidate::getScore).reversed())
            .limit(MAX_CANDIDATES)
            .collect(Collectors.toList());

    // Step 3: Build response
    List<Map<String, Object>> candidatesList = new ArrayList<>();
    for (OwnerCandidate candidate : ranked) {
      candidatesList.add(candidate.toMap());
    }

    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("fqn", fqn);
    resultData.put("entityType", entityType);
    resultData.put("candidates", candidatesList);
    resultData.put("candidateCount", candidatesList.size());

    // Narrative
    String narrative = generateNarrative(fqn, ranked);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(resultData)).narrative(narrative);

    Map<String, Object> result = new HashMap<>(envelope.build());
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("candidates", candidatesList);
    result.put("candidateCount", candidatesList.size());

    return result;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "SuggestOwnerForTool does not support limits enforcement.");
  }

  // ====================== Candidate sources (R5.3) ======================

  /**
   * Adds recent patchers as candidates. Queries ChangeEventRepository for the entity's
   * recent change events (last 90 days) and scores each unique patcher by frequency.
   */
  @VisibleForTesting
  void addRecentPatchers(
      Map<String, OwnerCandidate> candidateMap,
      EntityReference entityRef,
      String fqn,
      String entityType,
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider) {

    try {
      ChangeEventRepository changeEventRepo = changeEventRepoProvider.getChangeEventRepository();
      if (changeEventRepo == null) {
        LOG.debug("ChangeEventRepository not available, skipping recent patchers for '{}'", fqn);
        return;
      }
      long cutoffTs =
          LocalDateTime.now().minusDays(LOOKBACK_DAYS).toInstant(ZoneOffset.UTC).toEpochMilli();

      List<ChangeEvent> changeEvents =
          changeEventRepo.list(cutoffTs, List.of(), List.of(entityType), List.of(), List.of());

      // Count patch frequency per user for this entity
      // Cap total iterations to avoid OOM on large catalogs
      Map<String, Integer> patchCount = new HashMap<>();
      int maxIter = MAX_CANDIDATES * 20; // iterate at most 60 events total
      int iterated = 0;
      for (ChangeEvent ce : changeEvents) {
        if (iterated++ >= maxIter) break;
        if (ce.getEntityId() != null
            && ce.getEntityId().equals(entityRef.getId())
            && ce.getUserName() != null) {
          patchCount.merge(ce.getUserName(), 1, Integer::sum);
        }
      }

      // Add as candidates with 3× weight × frequency
      for (Map.Entry<String, Integer> entry : patchCount.entrySet()) {
        String userName = entry.getKey();
        double score = WEIGHT_RECENT_PATCHER * entry.getValue();
        OwnerCandidate candidate =
            candidateMap.computeIfAbsent(
                userName, k -> new OwnerCandidate(userName, "user", null, new ArrayList<>(), 0.0));
        candidate.addScore(score);
        candidate.addRationale(
            String.format(
                "mostFrequentPatcher: %d patch%s in last %d days (weight %.0f×%.0f=%s%.0f)",
                entry.getValue(),
                entry.getValue() == 1 ? "" : "es",
                LOOKBACK_DAYS,
                WEIGHT_RECENT_PATCHER,
                (double) entry.getValue(),
                score == (int) score ? "" : "",
                score));
      }
    } catch (Exception e) {
      LOG.debug("Could not fetch recent patchers for '{}': {}", fqn, e.getMessage());
    }
  }

  /**
   * Adds owners of immediate upstream entities as candidates.
   * Queries 1-hop upstream lineage and extracts owner names.
   */
  @VisibleForTesting
  void addUpstreamOwners(
      Map<String, OwnerCandidate> candidateMap,
      String fqn,
      String entityType,
      McpEntityBridge.LineageRepositoryProvider lineageProvider,
      McpEntityBridge.EntityFetcher entityFetcher) {

    try {
      var lineageRepo = lineageProvider.getLineageRepository();
      if (lineageRepo == null) {
        LOG.warn("Lineage repository not initialized — cannot fetch upstream owners for '{}'", fqn);
        return;
      }
      Map<String, Object> lineageData =
          JsonUtils.getMap(lineageRepo.getByName(entityType, fqn, MAX_UPSTREAM_HOPS, 0));

      Object nodesObj = lineageData.get("nodes");
      if (nodesObj instanceof Map<?, ?> nodesMap) {
        for (Map.Entry<?, ?> entry : nodesMap.entrySet()) {
          if (entry.getValue() instanceof Map<?, ?> node) {
            String nodeFqn = (String) ((Map<?, ?>) node).get("fullyQualifiedName");
            if (nodeFqn != null && !nodeFqn.equals(fqn)) {
              addOwnersFromEntity(
                  candidateMap,
                  nodeFqn,
                  entityType,
                  "upstreamOwner",
                  WEIGHT_UPSTREAM_OWNER,
                  entityFetcher);
            }
          }
        }
      } else if (nodesObj instanceof java.util.Set<?> nodes) {
        for (Object node : nodes) {
          if (node instanceof Map<?, ?> nodeMap) {
            String nodeFqn = (String) nodeMap.get("fullyQualifiedName");
            if (nodeFqn != null && !nodeFqn.equals(fqn)) {
              addOwnersFromEntity(
                  candidateMap,
                  nodeFqn,
                  entityType,
                  "upstreamOwner",
                  WEIGHT_UPSTREAM_OWNER,
                  entityFetcher);
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not fetch upstream owners for '{}': {}", fqn, e.getMessage());
    }
  }

  /**
   * Adds the domain default owner as a candidate.
   * Looks up the entity's domain and finds the domain's default owner.
   */
  @VisibleForTesting
  void addDomainDefaultOwner(
      Map<String, OwnerCandidate> candidateMap,
      EntityReference entityRef,
      String fqn,
      String entityType,
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher,
      McpEntityBridge.EntityFetcher entityFetcher) {

    try {
      Object entity = entityByRefFetcher.getEntity(entityRef, "domains", Include.NON_DELETED);
      if (entity == null) return;
      Map<String, Object> entityMap = JsonUtils.readValue(JsonUtils.pojoToJson(entity), Map.class);

      Object domainsObj = entityMap.get("domains");
      if (domainsObj instanceof List<?> domains) {
        for (Object domainObj : domains) {
          if (domainObj instanceof Map<?, ?> domain) {
            String domainFqn = (String) domain.get("fullyQualifiedName");
            if (domainFqn != null) {
              addOwnersFromEntity(
                  candidateMap,
                  domainFqn,
                  "domain",
                  "domainDefaultOwner",
                  WEIGHT_DOMAIN_DEFAULT,
                  entityFetcher);
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not fetch domain default owner for '{}': {}", fqn, e.getMessage());
    }
  }

  /**
   * Adds owners of sibling tables in the same schema as candidates.
   * Parses the FQN to extract the schema portion, then looks up schema children.
   */
  @VisibleForTesting
  void addSiblingOwners(
      Map<String, OwnerCandidate> candidateMap,
      String fqn,
      String entityType,
      McpEntityBridge.EntityFetcher entityFetcher) {

    try {
      // For tables, FQN format is: service.database.schema.table
      // Siblings are other tables in the same schema
      if (!"table".equals(entityType)) return;

      String schemaFqn = extractSchemaFqn(fqn);
      if (schemaFqn == null) return;

      Object schemaEntity =
          entityFetcher.getEntityByName("databaseSchema", schemaFqn, "tables", Include.NON_DELETED);
      if (schemaEntity == null) return;

      Map<String, Object> schemaMap =
          JsonUtils.readValue(JsonUtils.pojoToJson(schemaEntity), Map.class);

      Object tablesObj = schemaMap.get("tables");
      if (tablesObj instanceof List<?> tables) {
        for (Object tableObj : tables) {
          if (tableObj instanceof Map<?, ?> table) {
            String tableFqn = (String) table.get("fullyQualifiedName");
            if (tableFqn != null && !tableFqn.equals(fqn)) {
              addOwnersFromEntity(
                  candidateMap,
                  tableFqn,
                  entityType,
                  "siblingOwner",
                  WEIGHT_SIBLING_OWNER,
                  entityFetcher);
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not fetch sibling owners for '{}': {}", fqn, e.getMessage());
    }
  }

  // ====================== Helper: extract owners from entity ======================

  /**
   * Fetches an entity by FQN and adds its owners to the candidate map with the given weight.
   */
  private void addOwnersFromEntity(
      Map<String, OwnerCandidate> candidateMap,
      String entityFqn,
      String entityType,
      String rationalePrefix,
      double weight,
      McpEntityBridge.EntityFetcher entityFetcher) {

    try {
      Object entity =
          entityFetcher.getEntityByName(entityType, entityFqn, "owners", Include.NON_DELETED);
      if (entity == null) return;
      Map<String, Object> entityMap = JsonUtils.readValue(JsonUtils.pojoToJson(entity), Map.class);

      Object ownersObj = entityMap.get("owners");
      if (ownersObj instanceof List<?> ownersList) {
        for (Object o : ownersList) {
          if (o instanceof Map<?, ?> ownerEntry) {
            String ownerName = (String) ownerEntry.get("name");
            String ownerType = (String) ownerEntry.get("type");
            Object ownerId = ownerEntry.get("id");
            if (ownerName != null) {
              OwnerCandidate candidate =
                  candidateMap.computeIfAbsent(
                      ownerName,
                      k ->
                          new OwnerCandidate(
                              ownerName,
                              ownerType,
                              ownerId != null ? ownerId.toString() : null,
                              new ArrayList<>(),
                              0.0));
              candidate.addScore(weight);
              candidate.addRationale(
                  String.format("%s: from %s (weight %.0f)", rationalePrefix, entityFqn, weight));
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not resolve owners for '{}': {}", entityFqn, e.getMessage());
    }
  }

  // ====================== Helper: extract schema FQN ======================

  /**
   * Extracts the schema FQN from a table FQN.
   * Table FQN format: service.database.schema.table → returns service.database.schema
   */
  @VisibleForTesting
  static String extractSchemaFqn(String tableFqn) {
    if (tableFqn == null) return null;
    int lastDot = tableFqn.lastIndexOf('.');
    if (lastDot <= 0) return null;
    String withoutTable = tableFqn.substring(0, lastDot);
    // Verify at least 3 parts (service.database.schema)
    long dotCount = withoutTable.chars().filter(c -> c == '.').count();
    if (dotCount < 2) return null;
    return withoutTable;
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(String fqn, List<OwnerCandidate> candidates) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Suggested Owners for `").append(fqn).append("`\n\n");

    if (candidates.isEmpty()) {
      sb.append("No owner candidates found. Consider assigning a domain default owner.\n");
      return sb.toString();
    }

    sb.append("### Top Candidates\n\n");
    for (int i = 0; i < candidates.size(); i++) {
      OwnerCandidate c = candidates.get(i);
      sb.append(i + 1)
          .append(". **")
          .append(c.name)
          .append("** (score: ")
          .append(String.format("%.1f", c.score))
          .append(")\n");
      for (String rationale : c.rationale) {
        sb.append("   - ").append(rationale).append("\n");
      }
    }

    sb.append("\n### Next Step\n");
    sb.append(
        "Use `draft_ownership_patch` with the chosen owner to generate a review-ready JSONPatch.");

    return sb.toString();
  }

  // ====================== Owner Candidate model ======================

  /** Mutable candidate for owner suggestion, accumulated across multiple sources. */
  @VisibleForTesting
  static class OwnerCandidate {
    final String name;
    final String type;
    final String id;
    final List<String> rationale;
    double score;

    OwnerCandidate(String name, String type, String id, List<String> rationale, double score) {
      this.name = name;
      this.type = type;
      this.id = id;
      this.rationale = rationale;
      this.score = score;
    }

    void addScore(double delta) {
      this.score += delta;
    }

    void addRationale(String reason) {
      this.rationale.add(reason);
    }

    double getScore() {
      return score;
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("name", name);
      map.put("type", type);
      if (id != null) map.put("id", id);
      map.put("score", score);
      map.put("rationale", List.copyOf(rationale));
      return map;
    }
  }
}
