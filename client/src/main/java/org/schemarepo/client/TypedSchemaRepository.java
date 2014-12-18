/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.schemarepo.client;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import org.schemarepo.Repository;
import org.schemarepo.SchemaEntry;
import org.schemarepo.SchemaValidationException;
import org.schemarepo.Subject;
import org.schemarepo.SubjectConfig;
import org.schemarepo.client.converter.Converter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is a convenience class for interacting with a Schema Repository in an
 * efficient manner. It is (lazily) cached whenever possible and strongly-typed.
 *
 * N.B.: Currently, there is no cache eviction mechanism, so this can
 * potentially grow to unbounded sizes.
 */
public class TypedSchemaRepository<
        ID,       IdConverter       extends Converter<ID>,
        SCHEMA,   SchemaConverter   extends Converter<SCHEMA>,
        SUBJECT,  SubjectConverter  extends Converter<SUBJECT>,
        REPO extends Repository> {

  private REPO repo;
  private IdConverter convertId;
  private SchemaConverter convertSchema;
  private SubjectConverter convertSubject;
  private SubjectConfig.Builder defaultSubjectConfigBuilder;

  // Internal state

  private Map<SUBJECT, BiMap<ID, SCHEMA>> subjectToIdToSchemaCache;
  private Map<SUBJECT, BiMap<SCHEMA, ID>> subjectToSchemaToIdCache;

  // Constructors

  public TypedSchemaRepository(
          REPO repo,
          IdConverter idConverter,
          SchemaConverter schemaConverter,
          SubjectConverter subjectConverter,
          SubjectConfig.Builder defaultSubjectConfigBuilder) {
    this.repo = repo;
    this.convertId = idConverter;
    this.convertSchema = schemaConverter;
    this.convertSubject = subjectConverter;
    this.defaultSubjectConfigBuilder = defaultSubjectConfigBuilder;
    this.subjectToIdToSchemaCache = new HashMap<SUBJECT, BiMap<ID, SCHEMA>>();
    this.subjectToSchemaToIdCache = new HashMap<SUBJECT, BiMap<SCHEMA, ID>>();
  }

  public TypedSchemaRepository(
          REPO repo,
          IdConverter idConverter,
          SchemaConverter schemaConverter,
          SubjectConverter subjectConverter) {
    this(repo, idConverter, schemaConverter, subjectConverter,
            new SubjectConfig.Builder());
  }

  /**
   * Utility function for getting a idToSchemaCache for a given subject, or
   * initializing such cache if it does not exist yet.
   *
   * @param subjectName of the desired idToSchemaCache
   * @return the idToSchemaCache for the requested subject
   */
  private Map<ID, SCHEMA> getIdToSchemaCache(SUBJECT subjectName) {
    BiMap<ID, SCHEMA> idToSchemaCache = subjectToIdToSchemaCache.get(subjectName);
    if (idToSchemaCache == null) {
      synchronized (this) {
        // Checking again, in case it was initialized between the initial get
        // and the if check.
        idToSchemaCache = subjectToIdToSchemaCache.get(subjectName);
        if (idToSchemaCache == null) {
          idToSchemaCache = HashBiMap.create();
          subjectToIdToSchemaCache.put(subjectName, idToSchemaCache);
          subjectToSchemaToIdCache.put(subjectName, idToSchemaCache.inverse());
        }
      }
    }
    return idToSchemaCache;
  }

  /**
   * Utility function for getting a schemaToIdCache for a given subject, or
   * initializing such cache if it does not exist yet.
   *
   * @param subjectName of the desired schemaToIdCache
   * @return the schemaToIdCache for the requested subject
   */
  private Map<SCHEMA, ID> getSchemaToIdCache(SUBJECT subjectName){
    getIdToSchemaCache(subjectName); // Ensures initialization
    return subjectToSchemaToIdCache.get(subjectName);
  }

  // PUBLIC APIs BELOW

  /**
   * Gets a schema for a given subject and ID.
   *
   * This retrieves immutable data, hence it is cache-able indefinitely.
   *
   * @param subjectName containing the sought schema
   * @param id of the sought schema
   * @return the sought schema, or null if the subject does not exist or if it
   *         does not have any registered schemas yet.
   */
  public SCHEMA getSchema(SUBJECT subjectName, ID id) {
    Map<ID, SCHEMA> idToSchemaCache = getIdToSchemaCache(subjectName);
    SCHEMA schema = idToSchemaCache.get(id);

    if (schema == null) {
      Subject subject = repo.lookup(convertSubject.toString(subjectName));
      if (subject != null) {
        SchemaEntry schemaEntry = subject.lookupById(convertId.toString(id));
        if (schemaEntry != null) {
          schema = convertSchema.fromString(schemaEntry.getSchema());
          idToSchemaCache.put(id, schema); // idempotent
        }
      }
    }

    return schema;
  }

  /**
   * Gets the latest schema for a given subject.
   *
   * This retrieves mutable data, hence it is not cache-able and will always
   * result in a call to the underlying schema repo implementation.
   *
   * @param subjectName to get the latest schema for
   * @return the latest schema for the requested subject, or null if the subject
   *         does not exist or if it contains no schemas yet.
   */
  public SCHEMA getLatestSchema(SUBJECT subjectName) {
    SCHEMA schema = null;

    Subject subject = repo.lookup(convertSubject.toString(subjectName));
    if (subject != null) {
      SchemaEntry schemaEntry = subject.latest();
      if (schemaEntry != null) {
        schema = convertSchema.fromString(schemaEntry.getSchema());
        Map<SCHEMA, ID> schemaToIdCache = getSchemaToIdCache(subjectName);
        if (!schemaToIdCache.containsKey(schema)) {
          ID id = convertId.fromString(schemaEntry.getId());
          schemaToIdCache.put(schema, id); // idempotent
        }
      }
    }

    return schema;
  }

  /**
   * Gets a schema ID for a given subject and schema.
   *
   * This retrieves immutable data, hence it is cache-able indefinitely.
   *
   * @param subjectName containing the sought ID
   * @param schema corresponding to the sought ID
   * @return the sought ID, or null if the subject does not exist or if it
   *         does not have any registered schemas yet.
   */
  public ID getSchemaId(SUBJECT subjectName, SCHEMA schema) {
    Map<SCHEMA, ID> schemaToIdCache = getSchemaToIdCache(subjectName);
    ID id = schemaToIdCache.get(schema);

    if (id == null) {
      Subject subject = repo.lookup(convertSubject.toString(subjectName));
      if (subject != null) {
        SchemaEntry schemaEntry = subject.lookupBySchema(convertSchema.toString(schema));
        if (schemaEntry != null) {
          id = convertId.fromString(schemaEntry.getId());
          schemaToIdCache.put(schema, id); // idempotent
        }
      }
    }

    return id;
  }

  /**
   * Registers a new schema in the given subject and returns its ID. If the
   * schema already existed, its previous ID will be returned instead.
   *
   * This will only result in a call to the underlying schema repo
   * implementation if the schema is not already known in the local cache.
   *
   * N.B.: If the subject does not exist yet, it will be initialized with a
   * config built from the defaultSubjectConfigBuilder provided to the
   * {@link TypedSchemaRepository} at construction time. If you wish to use a
   * non-default configuration, you should first invoke
   * {@link #setConfig(Object, org.schemarepo.SubjectConfig)} before invoking
   * this function.
   *
   * @param subjectName to register the schema into
   * @param schema to register
   * @return the ID of the registered schema
   * @throws SchemaValidationException
   */
  public ID registerSchema(SUBJECT subjectName,
                           SCHEMA schema)
          throws SchemaValidationException {
    Map<SCHEMA, ID> schemaToIdCache = getSchemaToIdCache(subjectName);
    ID id = schemaToIdCache.get(schema);

    if (id == null) {
      Subject subject = repo.lookup(convertSubject.toString(subjectName));
      if (subject == null) {
        subject = repo.register(convertSubject.toString(subjectName),
                defaultSubjectConfigBuilder.build());
      }
      SchemaEntry schemaEntry = subject.register(convertSchema.toString(schema));
      id = convertId.fromString(schemaEntry.getId());
      schemaToIdCache.put(schema, id); // idempotent
    }

    return id;
  }

//  N.B.: I'd rather not support registerIfLatest in the typed client for now...
//  public ID registerSchemaIfLatest(SUBJECT subjectName,
//                                   SCHEMA newSchema,
//                                   ID latestId,
//                                   SCHEMA latestSchema)
//          throws SchemaValidationException {
//    // TODO: Determine if these are proper semantics for registerSchemaIfLatest....
//    Map<SCHEMA, ID> schemaToIdCache = getSchemaToIdCache(subjectName);
//    ID id = schemaToIdCache.get(newSchema);
//
//    if (id == null) {
//      SchemaEntry latestSchemaEntry = new SchemaEntry(
//              convertId.toString(latestId),
//              convertSchema.toString(latestSchema));
//      Subject subject = repo.lookup(convertSubject.toString(subjectName));
//      if (subject == null) {
//        subject = repo.register(convertSubject.toString(subjectName),
//                defaultSubjectConfigBuilder.build());
//      }
//      SchemaEntry schemaEntry = subject.registerIfLatest(
//              convertSchema.toString(newSchema),
//              latestSchemaEntry);
//      id = convertId.fromString(schemaEntry.getId());
//      schemaToIdCache.put(newSchema, id); // idempotent
//    }
//
//    return id;
//  }

  /**
   * This retrieves mutable data, hence it is not cache-able and will always
   * result in a call to the underlying schema repo implementation.
   *
   * @param subjectName to get the config for
   * @return the requested subject's config, or null if the subject does not exist
   */
  public SubjectConfig getConfig(SUBJECT subjectName) {
    Subject subject = repo.lookup(convertSubject.toString(subjectName));
    if (subject != null) {
      return subject.getConfig();
    } else {
      return null; // or throw exception?
    }
  }

  /**
   * This sets mutable data, hence it will always result in a call to the
   * underlying schema repo implementation.
   *
   * If the subject does not exist, it will be initialized with the given
   * config, but will not contain any ID/schema pair.
   *
   * @param subjectName to change the config for
   * @param subjectConfig to set for the given subject
   */
  public void setConfig(SUBJECT subjectName, SubjectConfig subjectConfig) {
    repo.register(convertSubject.toString(subjectName), subjectConfig);
  }

  /**
   * This retrieves mutable data, hence it is not cache-able and will always
   * result in a call to the underlying schema repo implementation.
   *
   * @return the list of all Subjects currently registered
   */
  public List<SUBJECT> getSubjects() {
    List<SUBJECT> subjects = Lists.newArrayList();
    for (Subject subject: repo.subjects()) {
      subjects.add(convertSubject.fromString(subject.getName()));
    }
    return subjects;
  }
}
