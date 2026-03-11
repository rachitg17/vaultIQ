package com.vaultiq.vaultiq.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;

@Slf4j
@Service
public class SupabaseStorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final String endpoint;

    public SupabaseStorageService(
            @Value("${supabase.s3.endpoint}") String endpoint,
            @Value("${supabase.s3.region}") String region,
            @Value("${supabase.s3.access-key}") String accessKey,
            @Value("${supabase.s3.secret-key}") String secretKey,
            @Value("${supabase.bucket}") String bucket) {

        this.endpoint = endpoint;
        this.bucket = bucket;

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true) // required for Supabase S3
                .build();

        log.info("SupabaseStorageService initialized — bucket: {}, endpoint: {}", bucket, endpoint);
    }

    /**
     * Upload file bytes to Supabase Storage.
     * @param key      object key e.g. "documents/uuid_filename.pdf"
     * @param data     raw file bytes
     * @param mimeType e.g. "application/pdf"
     */
    public void upload(String key, byte[] data, String mimeType) {
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(mimeType)
                    .contentLength((long) data.length)
                    .build();

            s3Client.putObject(req, RequestBody.fromBytes(data));
            log.info("Uploaded {} bytes to Supabase Storage: {}", data.length, key);
        } catch (Exception e) {
            log.error("Failed to upload to Supabase Storage: {}", e.getMessage());
            throw new RuntimeException("Storage upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Download file bytes from Supabase Storage.
     */
    public byte[] download(String key) {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            byte[] data = s3Client.getObjectAsBytes(req).asByteArray();
            log.info("Downloaded {} bytes from Supabase Storage: {}", data.length, key);
            return data;
        } catch (Exception e) {
            log.error("Failed to download from Supabase Storage: {}", e.getMessage());
            throw new RuntimeException("Storage download failed: " + e.getMessage(), e);
        }
    }

    /**
     * Delete file from Supabase Storage.
     */
    public void delete(String key) {
        try {
            DeleteObjectRequest req = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.deleteObject(req);
            log.info("Deleted from Supabase Storage: {}", key);
        } catch (Exception e) {
            log.warn("Failed to delete from Supabase Storage ({}): {}", key, e.getMessage());
        }
    }

    /**
     * Build the public URL for a stored file.
     * Supabase public URL format:
     * https://<project>.storage.supabase.co/storage/v1/object/public/<bucket>/<key>
     */
    public String getPublicUrl(String key) {
        // endpoint is https://<project>.storage.supabase.co/storage/v1/s3
        // public URL is  https://<project>.storage.supabase.co/storage/v1/object/public/<bucket>/<key>
        String base = endpoint.replace("/storage/v1/s3", "");
        return base + "/storage/v1/object/public/" + bucket + "/" + key;
    }
}