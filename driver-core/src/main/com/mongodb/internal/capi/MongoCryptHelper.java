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

package com.mongodb.internal.capi;

import com.mongodb.AwsCredential;
import com.mongodb.Block;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientException;
import com.mongodb.MongoClientSettings;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.SocketSettings;
import com.mongodb.crypt.capi.MongoCryptOptions;
import com.mongodb.internal.authentication.AwsCredentialHelper;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWrapper;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class MongoCryptHelper {

    public static MongoCryptOptions createMongoCryptOptions(final Map<String, Map<String, Object>> kmsProviders,
                                                            final Map<String, BsonDocument> namespaceToLocalSchemaDocumentMap) {
        MongoCryptOptions.Builder mongoCryptOptionsBuilder = MongoCryptOptions.builder();

        BsonDocument bsonKmsProviders = getKmsProvidersAsBsonDocument(kmsProviders);
        mongoCryptOptionsBuilder.kmsProviderOptions(bsonKmsProviders);
        mongoCryptOptionsBuilder.localSchemaMap(namespaceToLocalSchemaDocumentMap);
        mongoCryptOptionsBuilder.needsKmsCredentialsStateEnabled(true);
        return mongoCryptOptionsBuilder.build();
    }

    public static BsonDocument fetchCredentials(final Map<String, Map<String, Object>> kmsProviders) {
        BsonDocument kmsProvidersDocument = MongoCryptHelper.getKmsProvidersAsBsonDocument(kmsProviders);
        if (kmsProvidersDocument.containsKey("aws") && kmsProvidersDocument.get("aws").asDocument().isEmpty()) {
            AwsCredential awsCredential = AwsCredentialHelper.obtainFromEnvironment();
            if (awsCredential != null) {
                BsonDocument awsCredentialDocument = new BsonDocument();
                awsCredentialDocument.put("accessKeyId", new BsonString(awsCredential.getAccessKeyId()));
                awsCredentialDocument.put("secretAccessKey", new BsonString(awsCredential.getSecretAccessKey()));
                if (awsCredential.getSessionToken() != null) {
                    awsCredentialDocument.put("sessionToken", new BsonString(awsCredential.getSessionToken()));
                }
                kmsProvidersDocument.put("aws", awsCredentialDocument);
            }
        }
        return kmsProvidersDocument;
    }

    private static BsonDocument getKmsProvidersAsBsonDocument(final Map<String, Map<String, Object>> kmsProviders) {
        BsonDocument bsonKmsProviders = new BsonDocument();
        for (Map.Entry<String, Map<String, Object>> entry : kmsProviders.entrySet()) {
            bsonKmsProviders.put(entry.getKey(), new BsonDocumentWrapper<>(new Document(entry.getValue()), new DocumentCodec()));
        }
        return bsonKmsProviders;
    }

    @SuppressWarnings("unchecked")
    public static List<String> createMongocryptdSpawnArgs(final Map<String, Object> options) {
        List<String> spawnArgs = new ArrayList<String>();

        String path = options.containsKey("mongocryptdSpawnPath")
                ? (String) options.get("mongocryptdSpawnPath")
                : "mongocryptd";

        spawnArgs.add(path);
        if (options.containsKey("mongocryptdSpawnArgs")) {
            spawnArgs.addAll((List<String>) options.get("mongocryptdSpawnArgs"));
        }

        if (!spawnArgs.contains("--idleShutdownTimeoutSecs")) {
            spawnArgs.add("--idleShutdownTimeoutSecs");
            spawnArgs.add("60");
        }
        return spawnArgs;
    }

    public static MongoClientSettings createMongocryptdClientSettings(final String connectionString) {

        return MongoClientSettings.builder()
                .applyToClusterSettings(new Block<ClusterSettings.Builder>() {
                    @Override
                    public void apply(final ClusterSettings.Builder builder) {
                        builder.serverSelectionTimeout(10, TimeUnit.SECONDS);
                    }
                })
                .applyToSocketSettings(new Block<SocketSettings.Builder>() {
                    @Override
                    public void apply(final SocketSettings.Builder builder) {
                        builder.readTimeout(10, TimeUnit.SECONDS);
                        builder.connectTimeout(10, TimeUnit.SECONDS);
                    }
                })
                .applyConnectionString(new ConnectionString((connectionString != null)
                        ? connectionString : "mongodb://localhost:27020"))
                .build();
    }

    public static ProcessBuilder createProcessBuilder(final Map<String, Object> options) {
        return new ProcessBuilder(createMongocryptdSpawnArgs(options));
    }

    public static void startProcess(final ProcessBuilder processBuilder) {
        try {
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));
            processBuilder.start();
        } catch (Throwable t) {
            throw new MongoClientException("Exception starting mongocryptd process. Is `mongocryptd` on the system path?", t);
        }
    }

    private MongoCryptHelper() {
    }
}
