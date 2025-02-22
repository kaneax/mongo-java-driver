/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.reactivestreams.client.internal.crypt;

import com.mongodb.MongoClientException;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadPreference;
import com.mongodb.RequestContext;
import com.mongodb.ServerApi;
import com.mongodb.WriteConcernResult;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.internal.async.SingleResultCallback;
import com.mongodb.internal.bulk.DeleteRequest;
import com.mongodb.internal.bulk.InsertRequest;
import com.mongodb.internal.bulk.UpdateRequest;
import com.mongodb.internal.connection.AsyncConnection;
import com.mongodb.internal.connection.Connection;
import com.mongodb.internal.connection.MessageSettings;
import com.mongodb.internal.connection.QueryResult;
import com.mongodb.internal.connection.SplittablePayload;
import com.mongodb.internal.connection.SplittablePayloadBsonWriter;
import com.mongodb.internal.session.SessionContext;
import com.mongodb.internal.validator.MappedFieldNameValidator;
import com.mongodb.lang.Nullable;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonBinaryWriterSettings;
import org.bson.BsonDocument;
import org.bson.BsonWriter;
import org.bson.BsonWriterSettings;
import org.bson.FieldNameValidator;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.Codec;
import org.bson.codecs.Decoder;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.RawBsonDocumentCodec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.io.BasicOutputBuffer;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.mongodb.internal.operation.ServerVersionHelper.serverIsLessThanVersionFourDotTwo;
import static com.mongodb.reactivestreams.client.internal.MongoOperationPublisher.sinkToCallback;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;

class CryptConnection implements AsyncConnection {
    private static final CodecRegistry REGISTRY = fromProviders(new BsonValueCodecProvider());
    private static final int MAX_SPLITTABLE_DOCUMENT_SIZE = 2097152;

    private final AsyncConnection wrapped;
    private final Crypt crypt;

    CryptConnection(final AsyncConnection wrapped, final Crypt crypt) {
        this.wrapped = wrapped;
        this.crypt = crypt;
    }

    @Override
    public int getCount() {
        return wrapped.getCount();
    }

    @Override
    public CryptConnection retain() {
        wrapped.retain();
        return this;
    }

    @Override
    public int release() {
        return wrapped.release();
    }

    @Override
    public ConnectionDescription getDescription() {
        return wrapped.getDescription();
    }

    @Override
    public <T> void commandAsync(final String database, final BsonDocument command, final FieldNameValidator fieldNameValidator,
            final ReadPreference readPreference, final Decoder<T> commandResultDecoder,
            final SessionContext sessionContext, final ServerApi serverApi, final RequestContext requestContext,
            final SingleResultCallback<T> callback) {
        commandAsync(database, command, fieldNameValidator, readPreference, commandResultDecoder, sessionContext, serverApi, requestContext,
                true, null, null, callback);
    }

    @Override
    public <T> void commandAsync(final String database, final BsonDocument command, final FieldNameValidator commandFieldNameValidator,
            final ReadPreference readPreference, final Decoder<T> commandResultDecoder,
            final SessionContext sessionContext, final ServerApi serverApi, final RequestContext requestContext,
            final boolean responseExpected,
                                 @Nullable final SplittablePayload payload, @Nullable final FieldNameValidator payloadFieldNameValidator,
                                 final SingleResultCallback<T> callback) {

        if (serverIsLessThanVersionFourDotTwo(wrapped.getDescription())) {
            callback.onResult(null, new MongoClientException("Auto-encryption requires a minimum MongoDB version of 4.2"));
            return;
        }

        try {
            BasicOutputBuffer bsonOutput = new BasicOutputBuffer();
            BsonBinaryWriter bsonBinaryWriter = new BsonBinaryWriter(
                    new BsonWriterSettings(), new BsonBinaryWriterSettings(getDescription().getMaxDocumentSize()),
                    bsonOutput, getFieldNameValidator(payload, commandFieldNameValidator, payloadFieldNameValidator));
            BsonWriter writer = payload == null
                    ? bsonBinaryWriter
                    : new SplittablePayloadBsonWriter(bsonBinaryWriter, bsonOutput, createSplittablePayloadMessageSettings(), payload,
                                                      MAX_SPLITTABLE_DOCUMENT_SIZE);

            getEncoder(command).encode(writer, command, EncoderContext.builder().build());
            crypt.encrypt(database, new RawBsonDocument(bsonOutput.getInternalBuffer(), 0, bsonOutput.getSize()))
                    .flatMap((Function<RawBsonDocument, Mono<RawBsonDocument>>) encryptedCommand ->
                            Mono.create(sink -> wrapped.commandAsync(database, encryptedCommand, commandFieldNameValidator, readPreference,
                                                                     new RawBsonDocumentCodec(), sessionContext, serverApi, requestContext,
                                                                     responseExpected, null,
                                                                     null, sinkToCallback(sink))))
                    .flatMap(crypt::decrypt)
                    .map(decryptedResponse ->
                        commandResultDecoder.decode(new BsonBinaryReader(decryptedResponse.getByteBuffer().asNIO()),
                                                    DecoderContext.builder().build())
                    )
                    .subscribe(decryptedResult -> callback.onResult(decryptedResult, null), e -> callback.onResult(null, e));
        } catch (Throwable t) {
            callback.onResult(null, t);
        }
    }

    @SuppressWarnings("unchecked")
    private Codec<BsonDocument> getEncoder(final BsonDocument command) {
        return (Codec<BsonDocument>) REGISTRY.get(command.getClass());
    }

    private FieldNameValidator getFieldNameValidator(@Nullable final SplittablePayload payload,
                                                     final FieldNameValidator commandFieldNameValidator,
                                                     @Nullable final FieldNameValidator payloadFieldNameValidator) {
        if (payload == null) {
            return commandFieldNameValidator;
        }

        Map<String, FieldNameValidator> rootMap = new HashMap<>();
        rootMap.put(payload.getPayloadName(), payloadFieldNameValidator);
        return new MappedFieldNameValidator(commandFieldNameValidator, rootMap);
    }

    private MessageSettings createSplittablePayloadMessageSettings() {
        return MessageSettings.builder()
                .maxBatchCount(getDescription().getMaxBatchCount())
                .maxMessageSize(getDescription().getMaxMessageSize())
                .maxDocumentSize(getDescription().getMaxDocumentSize())
                .build();
    }


    // UNSUPPORTED METHODS for encryption/decryption

    @Override
    public void insertAsync(final MongoNamespace namespace, final boolean ordered, final InsertRequest insertRequest,
                            final RequestContext requestContext, final SingleResultCallback<WriteConcernResult> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public void updateAsync(final MongoNamespace namespace, final boolean ordered, final UpdateRequest updateRequest,
                            final RequestContext requestContext, final SingleResultCallback<WriteConcernResult> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public void deleteAsync(final MongoNamespace namespace, final boolean ordered, final DeleteRequest deleteRequest,
                            final RequestContext requestContext, final SingleResultCallback<WriteConcernResult> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public <T> void queryAsync(final MongoNamespace namespace, final BsonDocument queryDocument, final BsonDocument fields,
                               final int skip, final int limit, final int batchSize, final boolean secondaryOk,
                               final boolean tailableCursor, final boolean awaitData, final boolean noCursorTimeout, final boolean partial,
                               final boolean oplogReplay, final Decoder<T> resultDecoder,
                               final RequestContext requestContext, final SingleResultCallback<QueryResult<T>> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public <T> void getMoreAsync(final MongoNamespace namespace, final long cursorId, final int numberToReturn,
                                 final Decoder<T> resultDecoder, final RequestContext requestContext,
                                 final SingleResultCallback<QueryResult<T>> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public void killCursorAsync(final MongoNamespace namespace, final List<Long> cursors, final RequestContext requestContext,
                                final SingleResultCallback<Void> callback) {
        callback.onResult(null, new UnsupportedOperationException());
    }

    @Override
    public void markAsPinned(final Connection.PinningMode pinningMode) {
        wrapped.markAsPinned(pinningMode);
    }
}
