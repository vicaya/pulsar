/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests.integration.schema;

import static org.apache.pulsar.common.naming.TopicName.PUBLIC_TENANT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.tests.integration.schema.Schemas.Person;
import org.apache.pulsar.tests.integration.schema.Schemas.PersonConsumeSchema;
import org.apache.pulsar.tests.integration.schema.Schemas.Student;
import org.apache.pulsar.tests.integration.schema.Schemas.AvroLogicalType;
import org.apache.pulsar.tests.integration.suites.PulsarTestSuite;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
import org.joda.time.chrono.ISOChronology;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Test Pulsar Schema.
 */
@Slf4j
public class SchemaTest extends PulsarTestSuite {

    private PulsarClient client;
    private PulsarAdmin admin;

    @BeforeMethod
    public void setup() throws Exception {
        this.client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();
        this.admin = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarCluster.getHttpServiceUrl())
            .build();
    }

    @Test
    public void testCreateSchemaAfterDeletion() throws Exception {
        final String tenant = PUBLIC_TENANT;
        final String namespace = "test-namespace-" + randomName(16);
        final String topic = "test-create-schema-after-deletion";
        final String fqtn = TopicName.get(
             TopicDomain.persistent.value(),
             tenant,
             namespace,
             topic
         ).toString();

        admin.namespaces().createNamespace(
            tenant + "/" + namespace,
            Sets.newHashSet(pulsarCluster.getClusterName())
        );

        // Create a topic with `Person`
        try (Producer<Person> producer = client.newProducer(Schema.AVRO(Person.class))
             .topic(fqtn)
             .create()
        ) {
            Person person = new Person();
            person.setName("Tom Hanks");
            person.setAge(60);

            producer.send(person);

            log.info("Successfully published person : {}", person);
        }

        log.info("Deleting schema of topic {}", fqtn);
        // delete the schema
        admin.schemas().deleteSchema(fqtn);
        log.info("Successfully deleted schema of topic {}", fqtn);

        // after deleting the topic, try to create a topic with a different schema
        try (Producer<Student> producer = client.newProducer(Schema.AVRO(Student.class))
             .topic(fqtn)
             .create()
        ) {
            Student student = new Student();
            student.setName("Tom Jerry");
            student.setAge(30);
            student.setGpa(6);
            student.setGpa(10);

            producer.send(student);

            log.info("Successfully published student : {}", student);
        }
    }

    @Test
    public void testMultiVersionSchema() throws Exception {
        final String tenant = PUBLIC_TENANT;
        final String namespace = "test-namespace-" + randomName(16);
        final String topic = "test-multi-version-schema";
        final String fqtn = TopicName.get(
                TopicDomain.persistent.value(),
                tenant,
                namespace,
                topic
        ).toString();

        admin.namespaces().createNamespace(
                tenant + "/" + namespace,
                Sets.newHashSet(pulsarCluster.getClusterName())
        );

        Producer<Person> producer = client.newProducer(Schema.AVRO(
                SchemaDefinition.<Person>builder().withAlwaysAllowNull
                        (false).withSupportSchemaVersioning(true).
                        withPojo(Person.class).build()))
                .topic(fqtn)
                .create();

        Person person = new Person();
        person.setName("Tom Hanks");
        person.setAge(60);

        Consumer<PersonConsumeSchema> consumer = client.newConsumer(Schema.AVRO(
                SchemaDefinition.<PersonConsumeSchema>builder().withAlwaysAllowNull
                        (false).withSupportSchemaVersioning(true).
                        withPojo(PersonConsumeSchema.class).build()))
                .subscriptionName("test")
                .topic(fqtn)
                .subscribe();

        producer.send(person);
        log.info("Successfully published person : {}", person);

        PersonConsumeSchema personConsumeSchema = consumer.receive().getValue();
        assertEquals("Tom Hanks", personConsumeSchema.getName());
        assertEquals(60, personConsumeSchema.getAge());
        assertEquals("male", personConsumeSchema.getGender());

        producer.close();
        consumer.close();
        log.info("Successfully consumer personConsumeSchema : {}", personConsumeSchema);
    }

    @Test
    public void testAvroLogicalType() throws Exception {
        final String tenant = PUBLIC_TENANT;
        final String namespace = "test-namespace-" + randomName(16);
        final String topic = "test-logical-type-schema";
        final String fqtn = TopicName.get(
                TopicDomain.persistent.value(),
                tenant,
                namespace,
                topic
        ).toString();

        admin.namespaces().createNamespace(
                tenant + "/" + namespace,
                Sets.newHashSet(pulsarCluster.getClusterName())
        );

        AvroLogicalType messageForSend = AvroLogicalType.builder()
                .decimal(new BigDecimal("12.34"))
                .timestampMicros(System.currentTimeMillis() * 1000)
                .timestampMillis(new DateTime("2019-03-26T04:39:58.469Z", ISOChronology.getInstanceUTC()))
                .timeMillis(LocalTime.now())
                .timeMicros(System.currentTimeMillis() * 1000)
                .date(LocalDate.now())
                .build();

        Producer<AvroLogicalType> producer = client
                .newProducer(Schema.AVRO(AvroLogicalType.class))
                .topic(fqtn)
                .create();

        Consumer<AvroLogicalType> consumer = client
                .newConsumer(Schema.AVRO(AvroLogicalType.class))
                .topic(fqtn)
                .subscriptionName("test")
                .subscribe();

        producer.send(messageForSend);
        log.info("Successfully published avro logical type message : {}", messageForSend);

        AvroLogicalType received = consumer.receive().getValue();
        assertEquals(messageForSend.getDecimal(), received.getDecimal());
        assertEquals(messageForSend.getTimeMicros(), received.getTimeMicros());
        assertEquals(messageForSend.getTimeMillis(), received.getTimeMillis());
        assertEquals(messageForSend.getTimestampMicros(), received.getTimestampMicros());
        assertEquals(messageForSend.getTimestampMillis(), received.getTimestampMillis());
        assertEquals(messageForSend.getDate(), received.getDate());

        producer.close();
        consumer.close();

        log.info("Successfully consumer avro logical type message : {}", received);
    }

    @Test
    public void testAutoConsumeSchemaSubscribeFirst() throws Exception {
        final String tenant = PUBLIC_TENANT;
        final String namespace = "test-namespace-" + randomName(16);
        final String topic = "test-auto-consume-schema";
        final String fqtn = TopicName.get(
                TopicDomain.persistent.value(),
                tenant,
                namespace,
                topic
        ).toString();

        admin.namespaces().createNamespace(
                tenant + "/" + namespace,
                Sets.newHashSet(pulsarCluster.getClusterName())
        );

        Consumer<GenericRecord> consumer = client
                .newConsumer(Schema.AUTO_CONSUME())
                .topic(fqtn)
                .subscriptionName("test")
                .subscribe();

        Producer<Person> producer = client
                .newProducer(Schema.AVRO(Person.class))
                .topic(fqtn)
                .create();

        Person person = new Person();
        person.setName("Tom Hanks");
        person.setAge(60);
        producer.send(person);

        GenericRecord genericRecord = consumer.receive().getValue();

        assertEquals(genericRecord.getField("name"), "Tom Hanks");
        assertEquals(genericRecord.getField("age"), 60);

        consumer.close();
        producer.close();
    }

    @Test
    public void testPrimitiveSchemaTypeCompatibilityCheck() {
        List<Schema> schemas = new ArrayList<>();

        schemas.add(Schema.STRING);
        schemas.add(Schema.BYTES);
        schemas.add(Schema.INT8);
        schemas.add(Schema.INT16);
        schemas.add(Schema.INT32);
        schemas.add(Schema.INT64);
        schemas.add(Schema.BOOL);
        schemas.add(Schema.DOUBLE);
        schemas.add(Schema.FLOAT);
        schemas.add(Schema.DATE);
        schemas.add(Schema.TIME);
        schemas.add(Schema.TIMESTAMP);
        schemas.add(null);


        schemas.stream().forEach(schemaProducer -> {
            schemas.stream().forEach(schemaConsumer -> {
                try {
                    String topicName = schemaProducer.getSchemaInfo().getName() + schemaConsumer.getSchemaInfo().getName();
                    if (schemaProducer == null) {
                        client.newProducer()
                                .topic(topicName)
                                .create().close();
                    } else {
                        client.newProducer(schemaProducer)
                                .topic(topicName)
                                .create().close();
                    }

                    if (schemaConsumer == null) {
                        client.newConsumer()
                                .topic(topicName)
                                .subscriptionName("test")
                                .subscribe().close();
                    } else {
                        client.newConsumer(schemaConsumer)
                                .topic(topicName)
                                .subscriptionName("test")
                                .subscribe().close();
                    }

                    assertEquals(schemaProducer.getSchemaInfo().getType(),
                            schemaConsumer.getSchemaInfo().getType());

                } catch (PulsarClientException e) {
                    assertNotEquals(schemaProducer.getSchemaInfo().getType(),
                            schemaConsumer.getSchemaInfo().getType());
                }

            });
        });

    }

}

