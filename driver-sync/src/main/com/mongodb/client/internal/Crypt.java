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

package com.mongodb.client.internal;

import com.mongodb.MongoClientException;
import com.mongodb.MongoException;
import com.mongodb.MongoInternalException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.vault.DataKeyOptions;
import com.mongodb.client.model.vault.EncryptOptions;
import com.mongodb.crypt.capi.MongoCrypt;
import com.mongodb.crypt.capi.MongoCryptContext;
import com.mongodb.crypt.capi.MongoCryptException;
import com.mongodb.crypt.capi.MongoDataKeyOptions;
import com.mongodb.crypt.capi.MongoExplicitEncryptOptions;
import com.mongodb.crypt.capi.MongoKeyDecryptor;
import com.mongodb.internal.capi.MongoCryptHelper;
import com.mongodb.lang.Nullable;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.crypt.capi.MongoCryptContext.State;

class Crypt implements Closeable {

    private final MongoCrypt mongoCrypt;
    private final Map<String, Map<String, Object>> kmsProviders;
    private final CollectionInfoRetriever collectionInfoRetriever;
    private final CommandMarker commandMarker;
    private final KeyRetriever keyRetriever;
    private final KeyManagementService keyManagementService;
    private final boolean bypassAutoEncryption;
    private final MongoClient internalClient;

    /**
     * Create an instance to use for explicit encryption and decryption, and data key creation.
     *
     * @param mongoCrypt the mongoCrypt wrapper
     * @param kmsProviders the kms providers
     * @param keyRetriever the key retriever
     * @param keyManagementService the key management service
     */
    Crypt(final MongoCrypt mongoCrypt, final Map<String, Map<String, Object>> kmsProviders, final KeyRetriever keyRetriever,
            final KeyManagementService keyManagementService) {
        this(mongoCrypt, kmsProviders, null, null, keyRetriever, keyManagementService, false, null);
    }

    /**
     * Create an instance to use for auto-encryption and auto-decryption.
     *  @param mongoCrypt the mongoCrypt wrapper
     * @param kmsProviders the KMS provider credentials
     * @param collectionInfoRetriever the collection info retriever
     * @param commandMarker the command marker
     * @param keyRetriever the key retriever
     * @param keyManagementService the key management service
     */
    Crypt(final MongoCrypt mongoCrypt, final Map<String, Map<String, Object>> kmsProviders,
            @Nullable final CollectionInfoRetriever collectionInfoRetriever,
            @Nullable final CommandMarker commandMarker, final KeyRetriever keyRetriever,
            final KeyManagementService keyManagementService, final boolean bypassAutoEncryption,
            @Nullable final MongoClient internalClient) {
        this.mongoCrypt = mongoCrypt;
        this.kmsProviders = kmsProviders;
        this.collectionInfoRetriever = collectionInfoRetriever;
        this.commandMarker = commandMarker;
        this.keyRetriever = keyRetriever;
        this.keyManagementService = keyManagementService;
        this.bypassAutoEncryption = bypassAutoEncryption;
        this.internalClient = internalClient;
    }

    /**
     * Encrypt the given command
     *
     * @param databaseName the namespace
     * @param command   the unencrypted command
     * @return the encrypted command
     */
    public RawBsonDocument encrypt(final String databaseName, final RawBsonDocument command) {
        notNull("databaseName", databaseName);
        notNull("command", command);

        if (bypassAutoEncryption) {
            return command;
        }

        try {
            MongoCryptContext encryptionContext = mongoCrypt.createEncryptionContext(databaseName, command);

            try {
                return executeStateMachine(encryptionContext, databaseName);
            } finally {
                encryptionContext.close();
            }
        } catch (MongoCryptException e) {
            throw wrapInClientException(e);
        }
    }

    /**
     * Decrypt the given command response
     *
     * @param commandResponse the encrypted command response
     * @return the decrypted command response
     */
    RawBsonDocument decrypt(final RawBsonDocument commandResponse) {
        notNull("commandResponse", commandResponse);

        try {
            MongoCryptContext decryptionContext = mongoCrypt.createDecryptionContext(commandResponse);

            try {
                return executeStateMachine(decryptionContext, null);
            } finally {
                decryptionContext.close();
            }
        } catch (MongoCryptException e) {
            throw wrapInClientException(e);
        }
    }

    /**
     * Create a data key.
     *
     * @param kmsProvider the KMS provider to create the data key for
     * @param options     the data key options
     * @return the document representing the data key to be added to the key vault
     */
    BsonDocument createDataKey(final String kmsProvider, final DataKeyOptions options) {
        notNull("kmsProvider", kmsProvider);
        notNull("options", options);

        try {
            MongoCryptContext dataKeyCreationContext = mongoCrypt.createDataKeyContext(kmsProvider,
                    MongoDataKeyOptions.builder()
                            .keyAltNames(options.getKeyAltNames())
                            .masterKey(options.getMasterKey())
                            .build());

            try {
                return executeStateMachine(dataKeyCreationContext, null);
            } finally {
                dataKeyCreationContext.close();
            }
        } catch (MongoCryptException e) {
            throw wrapInClientException(e);
        }
    }

    /**
     * Encrypt the given value with the given options
     *
     * @param value the value to encrypt
     * @param options the options
     * @return the encrypted value
     */
    BsonBinary encryptExplicitly(final BsonValue value, final EncryptOptions options) {
        notNull("value", value);
        notNull("options", options);

        try {
            MongoExplicitEncryptOptions.Builder encryptOptionsBuilder = MongoExplicitEncryptOptions.builder()
                    .algorithm(options.getAlgorithm());

            if (options.getKeyId() != null) {
                encryptOptionsBuilder.keyId(options.getKeyId());
            }

            if (options.getKeyAltName() != null) {
                encryptOptionsBuilder.keyAltName(options.getKeyAltName());
            }

            MongoCryptContext encryptionContext = mongoCrypt.createExplicitEncryptionContext(
                    new BsonDocument("v", value), encryptOptionsBuilder.build());
            try {
                return executeStateMachine(encryptionContext, null).getBinary("v");
            } finally {
                encryptionContext.close();
            }
        } catch (MongoCryptException e) {
            throw wrapInClientException(e);
        }
    }

    /**
     * Decrypt the given encrypted value.
     *
     * @param value the encrypted value
     * @return the decrypted value
     */
    BsonValue decryptExplicitly(final BsonBinary value) {
        notNull("value", value);

        try {
            MongoCryptContext decryptionContext = mongoCrypt.createExplicitDecryptionContext(new BsonDocument("v", value));

            try {
                return executeStateMachine(decryptionContext, null).get("v");
            } finally {
                decryptionContext.close();
            }
        } catch (MongoCryptException e) {
            throw wrapInClientException(e);
        }
    }

    @Override
    @SuppressWarnings("try")
    public void close() {
        //noinspection EmptyTryBlock
        try (MongoCrypt mongoCrypt = this.mongoCrypt;
             CommandMarker commandMarker = this.commandMarker;
             MongoClient internalClient = this.internalClient
        ) {
            // just using try-with-resources to ensure they all get closed, even in the case of exceptions
        }
    }

    private RawBsonDocument executeStateMachine(final MongoCryptContext cryptContext, final String databaseName) {
        while (true) {
            State state = cryptContext.getState();
            switch (state) {
                case NEED_MONGO_COLLINFO:
                    collInfo(cryptContext, databaseName);
                    break;
                case NEED_MONGO_MARKINGS:
                    mark(cryptContext, databaseName);
                    break;
                case NEED_KMS_CREDENTIALS:
                    fetchCredentials(cryptContext);
                    break;
                case NEED_MONGO_KEYS:
                    fetchKeys(cryptContext);
                    break;
                case NEED_KMS:
                    decryptKeys(cryptContext);
                    break;
                case READY:
                    return cryptContext.finish();
                default:
                    throw new MongoInternalException("Unsupported encryptor state + " + state);
            }
        }
    }

    private void fetchCredentials(final MongoCryptContext cryptContext) {
        cryptContext.provideKmsProviderCredentials(MongoCryptHelper.fetchCredentials(kmsProviders));
    }

    private void collInfo(final MongoCryptContext cryptContext, final String databaseName) {
        try {
            BsonDocument collectionInfo = collectionInfoRetriever.filter(databaseName, cryptContext.getMongoOperation());
            if (collectionInfo != null) {
                cryptContext.addMongoOperationResult(collectionInfo);
            }
            cryptContext.completeMongoOperation();
        } catch (Throwable t) {
            throw MongoException.fromThrowableNonNull(t);
        }
    }

    private void mark(final MongoCryptContext cryptContext, final String databaseName) {
        try {
            RawBsonDocument markedCommand = commandMarker.mark(databaseName, cryptContext.getMongoOperation());
            cryptContext.addMongoOperationResult(markedCommand);
            cryptContext.completeMongoOperation();
        } catch (Throwable t) {
            throw wrapInClientException(t);
        }
    }

    private void fetchKeys(final MongoCryptContext keyBroker) {
        try {
            for (BsonDocument bsonDocument : keyRetriever.find(keyBroker.getMongoOperation())) {
                keyBroker.addMongoOperationResult(bsonDocument);
            }
            keyBroker.completeMongoOperation();
        } catch (Throwable t) {
            throw MongoException.fromThrowableNonNull(t);
        }
    }

    private void decryptKeys(final MongoCryptContext cryptContext) {
        try {
            MongoKeyDecryptor keyDecryptor = cryptContext.nextKeyDecryptor();
            while (keyDecryptor != null) {
                decryptKey(keyDecryptor);
                keyDecryptor = cryptContext.nextKeyDecryptor();
            }
            cryptContext.completeKeyDecryptors();
        } catch (Throwable t) {
            throw wrapInClientException(t);
        }
    }

    private void decryptKey(final MongoKeyDecryptor keyDecryptor) throws IOException {
        InputStream inputStream = keyManagementService.stream(keyDecryptor.getKmsProvider(), keyDecryptor.getHostName(),
                keyDecryptor.getMessage());
        try {
            int bytesNeeded = keyDecryptor.bytesNeeded();

            while (bytesNeeded > 0) {
                byte[] bytes = new byte[bytesNeeded];
                int bytesRead = inputStream.read(bytes, 0, bytes.length);
                keyDecryptor.feed(ByteBuffer.wrap(bytes, 0, bytesRead));
                bytesNeeded = keyDecryptor.bytesNeeded();
            }
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private MongoClientException wrapInClientException(final Throwable t) {
        return new MongoClientException("Exception in encryption library: " + t.getMessage(), t);
    }
}
