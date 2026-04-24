/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.EntityRelationshipRepository;
import org.openmetadata.service.jobs.JobDAO;
import org.openmetadata.service.search.SearchRepository;

/**
 * Unit test for {@link Entity#cleanup()}.
 *
 * <p>Verifies that after calling cleanup(), all 16 {@code @Getter @Setter} static repository fields
 * are null, the 3 private collections (ENTITY_REPOSITORY_MAP, ENTITY_TS_REPOSITORY_MAP,
 * ENTITY_LIST) are empty, and the {@code initializedRepositories} flag is false.
 *
 * <p>The test pre-populates some fields with mocks and the {@code initializedRepositories} flag +
 * {@code ENTITY_LIST} with non-default values via reflection to prove that cleanup() actively
 * changes state, not just that the default state happens to match.
 */
class EntityCleanupTest {

  /**
   * Populate representative fields with non-null mocks so we can verify cleanup() actively nulls
   * them. Only types that Mockito can instrument are mocked here; the remaining fields default to
   * null in the JVM but are still asserted after cleanup() as a regression safeguard.
   */
  @BeforeAll
  static void setUp() throws Exception {
    // Set fields that are easy to mock via their public Lombok setters
    Entity.setCollectionDAO(mock(CollectionDAO.class));
    Entity.setJobDAO(mock(JobDAO.class));
    Entity.setJdbi(mock(Jdbi.class));
    Entity.setSearchRepository(mock(SearchRepository.class));
    Entity.setEntityRelationshipRepository(mock(EntityRelationshipRepository.class));

    // Set initializedRepositories = true so cleanup() has to flip it back
    setStaticField("initializedRepositories", true);

    // Add a dummy entry to ENTITY_LIST so cleanup() has to clear it
    @SuppressWarnings("unchecked")
    Set<String> entityList = (Set<String>) getStaticField("ENTITY_LIST");
    entityList.add("testEntity_should_be_cleared");
  }

  @Test
  void cleanup_nullsAllRepositoryFields() throws Exception {
    // Sanity: verify pre-populated fields are non-null before cleanup
    assertNotNull(
        Entity.getCollectionDAO(), "collectionDAO should be non-null before cleanup (sanity)");
    assertNotNull(
        Entity.getSearchRepository(),
        "searchRepository should be non-null before cleanup (sanity)");
    assertTrue(
        getInitializedRepositories(), "initializedRepositories should be true before cleanup");
    assertFalse(Entity.getEntityList().isEmpty(), "ENTITY_LIST should be non-empty before cleanup");

    // --- Exercise ---
    Entity.cleanup();

    // --- Verify: all 16 @Getter @Setter static fields are null ---
    assertNull(Entity.getCollectionDAO(), "collectionDAO should be null after cleanup");
    assertNull(Entity.getJobDAO(), "jobDAO should be null after cleanup");
    assertNull(Entity.getJdbi(), "jdbi should be null after cleanup");
    assertNull(Entity.getTokenRepository(), "tokenRepository should be null after cleanup");
    assertNull(Entity.getPolicyRepository(), "policyRepository should be null after cleanup");
    assertNull(Entity.getRoleRepository(), "roleRepository should be null after cleanup");
    assertNull(Entity.getFeedRepository(), "feedRepository should be null after cleanup");
    assertNull(Entity.getLineageRepository(), "lineageRepository should be null after cleanup");
    assertNull(Entity.getUsageRepository(), "usageRepository should be null after cleanup");
    assertNull(Entity.getSystemRepository(), "systemRepository should be null after cleanup");
    assertNull(
        Entity.getChangeEventRepository(), "changeEventRepository should be null after cleanup");
    assertNull(Entity.getSearchRepository(), "searchRepository should be null after cleanup");
    assertNull(Entity.getAuditLogRepository(), "auditLogRepository should be null after cleanup");
    assertNull(
        Entity.getSuggestionRepository(), "suggestionRepository should be null after cleanup");
    assertNull(Entity.getTypeRepository(), "typeRepository should be null after cleanup");
    assertNull(
        Entity.getEntityRelationshipRepository(),
        "entityRelationshipRepository should be null after cleanup");

    // --- Verify: all 3 collections are empty ---
    assertTrue(Entity.getEntityList().isEmpty(), "ENTITY_LIST should be empty after cleanup");

    @SuppressWarnings("unchecked")
    Map<String, ?> entityRepoMap = (Map<String, ?>) getStaticField("ENTITY_REPOSITORY_MAP");
    assertTrue(entityRepoMap.isEmpty(), "ENTITY_REPOSITORY_MAP should be empty after cleanup");

    @SuppressWarnings("unchecked")
    Map<String, ?> entityTsRepoMap = (Map<String, ?>) getStaticField("ENTITY_TS_REPOSITORY_MAP");
    assertTrue(entityTsRepoMap.isEmpty(), "ENTITY_TS_REPOSITORY_MAP should be empty after cleanup");

    // --- Verify: initializedRepositories is false ---
    assertFalse(
        getInitializedRepositories(), "initializedRepositories should be false after cleanup");
  }

  // --- Reflection helpers for private static fields ---

  private static void setStaticField(String name, Object value) throws Exception {
    Field field = Entity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object getStaticField(String name) throws Exception {
    Field field = Entity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static boolean getInitializedRepositories() throws Exception {
    Field field = Entity.class.getDeclaredField("initializedRepositories");
    field.setAccessible(true);
    return field.getBoolean(null);
  }
}
