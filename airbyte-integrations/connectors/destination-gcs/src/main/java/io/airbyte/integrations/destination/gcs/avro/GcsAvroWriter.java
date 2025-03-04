/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.gcs.avro;

import alex.mojaki.s3upload.MultiPartOutputStream;
import alex.mojaki.s3upload.StreamTransferManager;
import com.amazonaws.services.s3.AmazonS3;
import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.integrations.destination.gcs.GcsDestinationConfig;
import io.airbyte.integrations.destination.gcs.util.GcsUtils;
import io.airbyte.integrations.destination.gcs.writer.BaseGcsWriter;
import io.airbyte.integrations.destination.gcs.writer.CommonWriter;
import io.airbyte.integrations.destination.gcs.writer.GscWriter;
import io.airbyte.integrations.destination.s3.S3Format;
import io.airbyte.integrations.destination.s3.avro.AvroRecordFactory;
import io.airbyte.integrations.destination.s3.avro.JsonToAvroSchemaConverter;
import io.airbyte.integrations.destination.s3.avro.S3AvroFormatConfig;
import io.airbyte.integrations.destination.s3.util.S3StreamTransferManagerHelper;
import io.airbyte.integrations.destination.s3.writer.S3Writer;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.allegro.schema.json2avro.converter.JsonAvroConverter;

public class GcsAvroWriter extends BaseGcsWriter implements S3Writer, GscWriter, CommonWriter {

  protected static final Logger LOGGER = LoggerFactory.getLogger(GcsAvroWriter.class);

  private final AvroRecordFactory avroRecordFactory;
  private final StreamTransferManager uploadManager;
  private final MultiPartOutputStream outputStream;
  private final DataFileWriter<GenericData.Record> dataFileWriter;
  private final String gcsFileLocation;
  private final String objectKey;

  public GcsAvroWriter(final GcsDestinationConfig config,
                       final AmazonS3 s3Client,
                       final ConfiguredAirbyteStream configuredStream,
                       final Timestamp uploadTimestamp,
                       final JsonAvroConverter converter)
      throws IOException {
    this(config, s3Client, configuredStream, uploadTimestamp, converter, null);
  }

  public GcsAvroWriter(final GcsDestinationConfig config,
                       final AmazonS3 s3Client,
                       final ConfiguredAirbyteStream configuredStream,
                       final Timestamp uploadTimestamp,
                       final JsonAvroConverter converter,
                       final JsonNode airbyteSchema)
      throws IOException {
    super(config, s3Client, configuredStream);

    Schema schema = (airbyteSchema == null ? GcsUtils.getDefaultAvroSchema(stream.getName(), stream.getNamespace(), true)
        : new JsonToAvroSchemaConverter().getAvroSchema(airbyteSchema, stream.getName(),
            stream.getNamespace(), true, false, false, true));
    LOGGER.info("Avro schema : {}", schema);
    final String outputFilename = BaseGcsWriter.getOutputFilename(uploadTimestamp, S3Format.AVRO);
    objectKey = String.join("/", outputPrefix, outputFilename);
    gcsFileLocation = String.format("gs://%s/%s", config.getBucketName(), objectKey);

    LOGGER.info("Full GCS path for stream '{}': {}/{}", stream.getName(), config.getBucketName(),
        objectKey);

    this.avroRecordFactory = new AvroRecordFactory(schema, converter);
    this.uploadManager = S3StreamTransferManagerHelper.getDefault(
        config.getBucketName(), objectKey, s3Client, config.getFormatConfig().getPartSize());
    // We only need one output stream as we only have one input stream. This is reasonably performant.
    this.outputStream = uploadManager.getMultiPartOutputStreams().get(0);

    final S3AvroFormatConfig formatConfig = (S3AvroFormatConfig) config.getFormatConfig();
    // The DataFileWriter always uses binary encoding.
    // If json encoding is needed in the future, use the GenericDatumWriter directly.
    this.dataFileWriter = new DataFileWriter<>(new GenericDatumWriter<Record>())
        .setCodec(formatConfig.getCodecFactory())
        .create(schema, outputStream);
  }

  @Override
  public void write(final UUID id, final AirbyteRecordMessage recordMessage) throws IOException {
    dataFileWriter.append(avroRecordFactory.getAvroRecord(id, recordMessage));
  }

  @Override
  public void write(JsonNode formattedData) throws IOException {
    GenericData.Record record = avroRecordFactory.getAvroRecord(formattedData);
    dataFileWriter.append(record);
  }

  @Override
  public String getFileLocation() {
    return gcsFileLocation;
  }

  @Override
  protected void closeWhenSucceed() throws IOException {
    dataFileWriter.close();
    outputStream.close();
    uploadManager.complete();
  }

  @Override
  protected void closeWhenFail() throws IOException {
    dataFileWriter.close();
    outputStream.close();
    uploadManager.abort();
  }

  @Override
  public S3Format getFileFormat() {
    return S3Format.AVRO;
  }

  @Override
  public String getOutputPath() {
    return objectKey;
  }

}
