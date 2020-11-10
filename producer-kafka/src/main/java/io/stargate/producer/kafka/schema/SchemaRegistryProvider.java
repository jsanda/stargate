/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.producer.kafka.schema;

import static io.stargate.producer.kafka.schema.SchemaConstants.DATA_FIELD_NAME;
import static io.stargate.producer.kafka.schema.SchemaConstants.OPERATION_FIELD_NAME;
import static io.stargate.producer.kafka.schema.SchemaConstants.TIMESTAMP_FIELD_NAME;
import static io.stargate.producer.kafka.schema.SchemaConstants.VALUE_FIELD_NAME;

import com.datastax.oss.driver.shaded.guava.common.annotations.VisibleForTesting;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Table;
import io.stargate.producer.kafka.mapping.MappingService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.SchemaBuilder.FieldAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaRegistryProvider implements SchemaProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(SchemaRegistryProvider.class);

  private static final int SCHEMA_REGISTRY_MAX_CAPACITY = 1000;

  private final SchemaRegistryClient schemaRegistryClient;

  private MappingService mappingService;
  @VisibleForTesting Map<String, Integer> schemaIdPerSubject = new ConcurrentHashMap<>();

  public SchemaRegistryProvider(String schemaRegistryUrl, MappingService mappingService) {
    this(
        new CachedSchemaRegistryClient(schemaRegistryUrl, SCHEMA_REGISTRY_MAX_CAPACITY),
        mappingService);
  }

  public SchemaRegistryProvider(
      SchemaRegistryClient schemaRegistryClient, MappingService mappingService) {
    this.schemaRegistryClient = schemaRegistryClient;
    this.mappingService = mappingService;
  }

  @Override
  public Schema getKeySchemaForTopic(String topicName) {
    String subjectName = constructKeyRecordName(topicName);
    return getSchemaBySubject(subjectName);
  }

  @Override
  public Schema getValueSchemaForTopic(String topicName) {
    String subjectName = constructValueRecordName(topicName);
    return getSchemaBySubject(subjectName);
  }

  @Override
  public void createOrUpdateSchema(Table table) {
    createOrUpdateKeySchema(table);
    createOrUpdateValueSchema(table);
  }

  private Schema getSchemaBySubject(String subjectName) {
    Integer schemaId = schemaIdPerSubject.get(subjectName);
    if (schemaId == null) {
      return getLatestSchemaBySubject(subjectName);
    }

    return getSchemaBySubjectAndId(subjectName, schemaId);
  }

  private Schema getLatestSchemaBySubject(String subjectName) {
    // try to fetch the latest schema
    Optional<SchemaMetadata> latestSchemaMetadata = getLatestSchemaMetadata(subjectName);
    if (!latestSchemaMetadata.isPresent()) {
      throw new IllegalStateException(
          "The getSchemaBySubject was called before createOrUpdateSchema and there is no existing schema created for subject: "
              + subjectName);
    }
    return getSchemaBySubjectAndId(subjectName, latestSchemaMetadata.get().getId());
  }

  private Optional<SchemaMetadata> getLatestSchemaMetadata(String subjectName) {
    try {
      return Optional.of(schemaRegistryClient.getLatestSchemaMetadata(subjectName));
    } catch (IOException | RestClientException e) {
      LOGGER.warn("There is no schema for subject: " + subjectName, e);
      return Optional.empty();
    }
  }

  private Schema getSchemaBySubjectAndId(String subjectName, Integer schemaId) {
    try {
      return (Schema)
          schemaRegistryClient.getSchemaBySubjectAndId(subjectName, schemaId).rawSchema();
    } catch (IOException | RestClientException e) {
      throw new SchemaRegistryException(
          "Problem when get schema for subject: " + subjectName + " and schema id: " + schemaId, e);
    }
  }

  private void createOrUpdateValueSchema(Table table) {
    ParsedSchema valueSchema = new AvroSchema(constructValueSchema(table));
    String subject = constructValueRecordName(table);

    int schemaId = registerSchema(valueSchema, subject);

    LOGGER.info(
        "Registered valueSchema: {}, for subject: {} and id: {}", valueSchema, subject, schemaId);
  }

  private void createOrUpdateKeySchema(Table table) {
    ParsedSchema keySchema = new AvroSchema(constructKeySchema(table));
    String subject = constructKeyRecordName(table);

    int schemaId = registerSchema(keySchema, subject);

    LOGGER.info(
        "Registered keySchema: {}, for subject: {} and id: {}", keySchema, subject, schemaId);
  }

  private int registerSchema(ParsedSchema schema, String subject) {
    try {
      int schemaId = schemaRegistryClient.register(subject, schema);
      schemaIdPerSubject.put(subject, schemaId);
      return schemaId;
    } catch (IOException | RestClientException e) {
      throw new SchemaRegistryException(
          "Problem when create or update schema for subject: " + subject, e);
    }
  }

  protected Schema constructKeySchema(Table table) {
    String keyRecordName = constructKeyRecordName(table);
    FieldAssembler<Schema> keyBuilder = SchemaBuilder.record(keyRecordName).fields();

    for (Column columnMetadata : table.partitionKeyColumns()) {
      Schema avroFieldSchema = CqlToAvroTypeConverter.toAvroType(columnMetadata.type());
      keyBuilder.name(columnMetadata.name()).type(avroFieldSchema).noDefault();
    }
    return keyBuilder.endRecord();
  }

  protected Schema constructValueSchema(Table table) {
    List<Schema> partitionKeys =
        constructUnionWithRequiredValueFieldsSchema(table.partitionKeyColumns());
    List<Schema> clusteringKeys =
        constructUnionWithRequiredValueFieldsSchema(table.clusteringKeyColumns());
    List<Schema> columns = constructUnionWithOptionalValueFieldsSchema(table.columns());
    Schema fieldsSchema = constructFieldsSchema(partitionKeys, clusteringKeys, columns, table);

    String valueRecordName = constructValueRecordName(table);
    return SchemaBuilder.record(valueRecordName)
        .fields()
        .requiredString(OPERATION_FIELD_NAME)
        .requiredLong(TIMESTAMP_FIELD_NAME)
        .name(DATA_FIELD_NAME)
        .type(fieldsSchema)
        .noDefault()
        .endRecord();
  }

  private Schema constructFieldsSchema(
      List<Schema> partitionKeys, List<Schema> clusteringKeys, List<Schema> columns, Table table) {
    String dataRecordName = constructDataRecordName(table);
    FieldAssembler<Schema> fields = SchemaBuilder.record(dataRecordName).fields();
    addToFields(partitionKeys, fields);
    addToFields(clusteringKeys, fields);
    addToFields(columns, fields);
    return fields.endRecord();
  }

  private void addToFields(List<Schema> fieldsToAdd, FieldAssembler<Schema> fields) {
    for (Schema schema : fieldsToAdd) {
      if (!schema.getType().equals(Type.UNION)) {
        throw new IllegalStateException(
            String.format("The type for %s should be UNION but is: %s", schema, schema.getType()));
      }
      String fieldName = schema.getTypes().get(1).getName();
      fields.name(fieldName).type(schema).withDefault(null);
    }
  }

  private List<Schema> constructUnionWithRequiredValueFieldsSchema(List<Column> tableMetadata) {
    List<Schema> fields = new ArrayList<>();
    for (Column columnMetadata : tableMetadata) {
      Schema field =
          SchemaBuilder.record(columnMetadata.name())
              .fields()
              .name(VALUE_FIELD_NAME)
              .type(CqlToAvroTypeConverter.toAvroType(columnMetadata.type()))
              .noDefault()
              .endRecord();
      fields.add(SchemaBuilder.unionOf().nullType().and().type(field).endUnion());
    }
    return fields;
  }

  private List<Schema> constructUnionWithOptionalValueFieldsSchema(List<Column> columns) {
    List<Schema> partitionKeys = new ArrayList<>();
    for (Column columnMetadata : columns) {
      Schema field =
          SchemaBuilder.record(columnMetadata.name())
              .fields()
              .name(VALUE_FIELD_NAME)
              .type()
              .optional()
              .type(CqlToAvroTypeConverter.toAvroType(columnMetadata.type()))
              .endRecord();
      partitionKeys.add(SchemaBuilder.unionOf().nullType().and().type(field).endUnion());
    }
    return partitionKeys;
  }

  private String constructKeyRecordName(String topicName) {
    return String.format("%s.Key", topicName);
  }

  private String constructKeyRecordName(Table table) {
    return constructKeyRecordName(mappingService.getTopicNameFromTableMetadata(table));
  }

  private String constructValueRecordName(Table table) {
    return constructValueRecordName(mappingService.getTopicNameFromTableMetadata(table));
  }

  private String constructValueRecordName(String topicName) {
    return String.format("%s.Value", topicName);
  }

  private String constructDataRecordName(Table table) {
    return String.format("%s.Data", mappingService.getTopicNameFromTableMetadata(table));
  }
}
