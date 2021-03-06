/*
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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.loadtests;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.beam.sdk.util.CoderUtils.encodeToByteArray;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.io.synthetic.SyntheticBoundedSource;
import org.apache.beam.sdk.io.synthetic.SyntheticOptions;
import org.apache.beam.sdk.io.synthetic.SyntheticSourceOptions;
import org.apache.beam.sdk.options.ApplicationNameOptions;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Pipeline that generates synthetic data and publishes it in PubSub topic.
 *
 * <p>To run it manually, use the following command:
 *
 * <pre>
 *  ./gradlew :beam-sdks-java-load-tests:run -PloadTest.args='
 *    --pubSubTopic=TOPIC_NAME
 *    --kafkaBootstrapServerAddress=SERVER_ADDRESS
 *    --kafkaTopic=KAFKA_TOPIC_NAME
 *    --sourceOptions={"numRecords":1000,...}'
 *    -PloadTest.mainClass="org.apache.beam.sdk.loadtests.SyntheticDataPublisher"
 *  </pre>
 *
 * <p>If parameters related to a specific sink are provided (Kafka or PubSub), the pipeline writes
 * to the sink. Writing to both sinks is also acceptable.
 */
public class SyntheticDataPublisher {

  private static final KvCoder<byte[], byte[]> RECORD_CODER =
      KvCoder.of(ByteArrayCoder.of(), ByteArrayCoder.of());

  private static Options options;

  /** Options for the pipeline. */
  public interface Options extends PipelineOptions, ApplicationNameOptions {

    @Description("Options for synthetic source")
    @Validation.Required
    String getSourceOptions();

    void setSourceOptions(String sourceOptions);

    @Description("PubSub topic to publish to")
    String getPubSubTopic();

    void setPubSubTopic(String topic);

    @Description("Kafka server address")
    String getKafkaBootstrapServerAddress();

    void setKafkaBootstrapServerAddress(String address);

    @Description("Kafka topic")
    String getKafkaTopic();

    void setKafkaTopic(String topic);
  }

  public static void main(String[] args) throws IOException {
    options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    SyntheticSourceOptions sourceOptions =
        SyntheticOptions.fromJsonString(options.getSourceOptions(), SyntheticSourceOptions.class);

    Pipeline pipeline = Pipeline.create(options);
    PCollection<KV<byte[], byte[]>> syntheticData =
        pipeline.apply("Read synthetic data", Read.from(new SyntheticBoundedSource(sourceOptions)));

    if (options.getKafkaBootstrapServerAddress() != null && options.getKafkaTopic() != null) {
      writeToKafka(syntheticData);
    }
    if (options.getPubSubTopic() != null) {
      writeToPubSub(syntheticData);
    }
    pipeline.run().waitUntilFinish();
  }

  private static void writeToPubSub(PCollection<KV<byte[], byte[]>> collection) {
    collection
        .apply("Map to PubSub messages", MapElements.via(new MapBytesToPubSubMessage()))
        .apply("Write to PubSub", PubsubIO.writeMessages().to(options.getPubSubTopic()));
  }

  private static void writeToKafka(PCollection<KV<byte[], byte[]>> collection) {
    collection
        .apply("Map to Kafka messages", MapElements.via(new MapKVToString()))
        .apply(
            "Write to Kafka",
            KafkaIO.<Void, String>write()
                .withBootstrapServers(options.getKafkaBootstrapServerAddress())
                .withTopic(options.getKafkaTopic())
                .withValueSerializer(StringSerializer.class)
                .values());
  }

  private static class MapKVToString extends SimpleFunction<KV<byte[], byte[]>, String> {
    @Override
    public String apply(KV<byte[], byte[]> input) {
      return String.format(
          "{%s,%s}", Arrays.toString(input.getKey()), Arrays.toString(input.getValue()));
    }
  }

  private static class MapBytesToPubSubMessage
      extends SimpleFunction<KV<byte[], byte[]>, PubsubMessage> {
    @Override
    public PubsubMessage apply(KV<byte[], byte[]> input) {
      return new PubsubMessage(encodeInputElement(input), encodeInputElementToMapOfStrings(input));
    }
  }

  private static byte[] encodeInputElement(KV<byte[], byte[]> input) {
    try {
      return encodeToByteArray(RECORD_CODER, input);
    } catch (CoderException e) {
      throw new RuntimeException(String.format("Couldn't encode element. Exception: %s", e));
    }
  }

  private static Map<String, String> encodeInputElementToMapOfStrings(KV<byte[], byte[]> input) {
    String key = new String(input.getKey(), UTF_8);
    String value = new String(input.getValue(), UTF_8);
    HashMap<String, String> map = new HashMap<>();
    map.put(key, value);
    return map;
  }
}
