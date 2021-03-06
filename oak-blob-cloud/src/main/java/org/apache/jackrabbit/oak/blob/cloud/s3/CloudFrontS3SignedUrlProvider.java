/*
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


package org.apache.jackrabbit.oak.blob.cloud.s3;

import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
import com.google.common.io.Closeables;
import org.apache.commons.codec.binary.Base64;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.conversion.URIProvider;
import org.apache.jackrabbit.oak.plugins.value.OakValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.Value;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


/**
 * Adapts from Value to URI where Value has a Binary value.
 * If running as an OSGi Component would expect an OSGi AdapterManager to pick it up.
 * other.
 *
 * To generate keys in PKCS8 format use OpenSSL
 * openssl genrsa -out private_key.pem 1024
 * openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in private_key.pem -out private_key.pkcs8
 * See http://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-trusted-signers.html#private-content-creating-cloudfront-key-pairs for
 * details on how to configure CloudFront.
 */
@Component(immediate = true, metatype = true, policy = ConfigurationPolicy.REQUIRE)
@Service(URIProvider.class)
public class CloudFrontS3SignedUrlProvider implements URIProvider {

    @Property( description = "The cloud front URL, including a trailing slash. Normally this is the http://<coudfrontdomain>/")
    public static final String CLOUD_FRONT_URL = "cloudFrontUrl";

    @Property(intValue = 60, description = "Time each signed url is valid for before it expires, in seconds.")
    public static final String TTL = "ttl";

    @Property(intValue = 100, description = "Minimum size over which a binary is redirected, in kb.")
    public static final String MIN_SIZE = "minSize";

    @Property(description = "Path to the PKCS8 formatted private key file, probably an absolute path.")
    public static final String PRIVATE_KEY_FILE = "privateKeyFile";

    @Property(description = "The keypair ID generated by AWS Console when the public key was generated or uploaded.")
    public static final String KEY_PAIR_ID = "keyPairId";

    public static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    public static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudFrontS3SignedUrlProvider.class);
    private String cloudFrontUrl;
    private long ttl;
    private String keyPairId;
    private RSAPrivateKey privateKey;
    private long minimumSize;

    /**
     * Default Constructor used by OSGi.
     */
    public CloudFrontS3SignedUrlProvider() {
    }

    /**
     * Non OSGi IoC constructor, close must be called when done.
     * @param cloudFrontUrl
     * @param ttl
     * @param privateKeyPEM
     * @param privateKeyId
     * @throws InvalidKeySpecException
     * @throws NoSuchAlgorithmException
     */
    public CloudFrontS3SignedUrlProvider(String cloudFrontUrl,
                                         long ttl,
                                         long minSize,
                                         String privateKeyPEM,
                                         String privateKeyId) throws InvalidKeySpecException, NoSuchAlgorithmException {
        init(cloudFrontUrl, ttl, minSize, privateKeyPEM, privateKeyId);
    }

    public void close() {
        deactivate(new HashMap<String, Object>());
    }

    @Deactivate
    public void deactivate(Map<String, Object> properties) {
    }


    @Activate
    public void activate(Map<String, Object> properties) throws InvalidKeySpecException, NoSuchAlgorithmException, IOException {
        LOGGER.debug("Property {}: {} ",CLOUD_FRONT_URL, properties.get(CLOUD_FRONT_URL));
        LOGGER.debug("Property {}: {} ",TTL, properties.get(TTL));
        LOGGER.debug("Property {}: {} ", PRIVATE_KEY_FILE, properties.get(PRIVATE_KEY_FILE));
        LOGGER.debug("Property {}: {} ",KEY_PAIR_ID, properties.get(KEY_PAIR_ID));
        LOGGER.debug("Property {}: {} ",MIN_SIZE, properties.get(MIN_SIZE));
        init((String) properties.get(CLOUD_FRONT_URL),
                (Integer) properties.get(TTL),
                (Integer) properties.get(MIN_SIZE),
                loadPrivateKey((String) properties.get(PRIVATE_KEY_FILE)),
                (String) properties.get(KEY_PAIR_ID));
    }

    private String loadPrivateKey(String keyFile) throws IOException {
        FileReader fr = null;
        StringBuilder key = new StringBuilder();
        try {
            fr = new FileReader(keyFile);
            char[] b = new char[4096];
            for (; ; ) {
                int i = fr.read(b);
                if (i < 0) {
                    break;
                }
                key.append(b, 0, i);
            }
        } finally {
            Closeables.close(fr, false);
        }
        return key.toString();
    }

    private void init(String cloudFrontUrl, long ttl, long minSize, String privateKeyPEM, String privateKeyId)
        throws InvalidKeySpecException, NoSuchAlgorithmException {
        this.cloudFrontUrl = cloudFrontUrl;
        this.ttl = ttl;
        this.minimumSize = minSize*1024;
        this.privateKey = getPrivateKey(privateKeyPEM);
        this.keyPairId = privateKeyId;
    }


    @Override
    public URI toURI(Value value) {
        // The conversion is javax.jcr.Value -> URI, but for Oak all Values are OakValues and we can only do it for Values.
        if ( value instanceof OakValue ) {
            try {
                Blob b = ((OakValue) value).getBlob();
                if (b != null) {
                    if ( b.length() > minimumSize ) {
                        String contentId = b.getContentIdentity();
                        if (contentId != null) {
                            URI uri = new URI(signS3Url(contentId, ttl, cloudFrontUrl, keyPairId, privateKey));
                            LOGGER.info("Generated URI {} ", uri.toString());
                            return uri;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unable to get or sign content identity",e);
            }

        }
        return null;
    }


    /**
     * Convert the content identity to a S3 url, see the {@link org.apache.jackrabbit.oak.blob.cloud.s3.S3Backend} class.
     * @param contentIdentity
     * @return
     */
    @Nonnull
    private String getS3Key(@Nonnull  String contentIdentity) {
        return contentIdentity.substring(0, 4) + "-" + contentIdentity.substring(4);
    }

    @Nonnull
    private String signS3Url(@Nonnull String contentIdentity, long ttl, @Nonnull String cloudFrontUrl,
                             @Nonnull String keyPairId, @Nonnull RSAPrivateKey privateKey)
        throws InvalidKeySpecException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, UnsupportedEncodingException {

        long expiry = (System.currentTimeMillis()/1000)+ttl;
        StringBuilder urlToSign = new StringBuilder();

        urlToSign.append(cloudFrontUrl)
                .append(getS3Key(contentIdentity));
        return CloudFrontUrlSigner.getSignedURLWithCannedPolicy(urlToSign.toString(), keyPairId, privateKey, new Date(expiry));
    }


    private RSAPrivateKey getPrivateKey(String privateKeyPKCS8) throws NoSuchAlgorithmException, InvalidKeySpecException {
        int is = privateKeyPKCS8.indexOf(BEGIN_PRIVATE_KEY);
        int ie = privateKeyPKCS8.indexOf(END_PRIVATE_KEY);
        if (ie < 0 || is < 0) {
            throw new IllegalArgumentException("Private Key is not correctly encoded, need a PEM encoded key with " +
                    "-----BEGIN PRIVATE KEY----- headers to indicate PKCS8 encoding.");
        }
        privateKeyPKCS8 = privateKeyPKCS8.substring(is + BEGIN_PRIVATE_KEY.length(), ie).trim();
        byte[] privateKeyBytes = Base64.decodeBase64(privateKeyPKCS8);

        // load the private key
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        KeySpec ks = new PKCS8EncodedKeySpec(privateKeyBytes);
        return (RSAPrivateKey) keyFactory.generatePrivate(ks);
    }
}
