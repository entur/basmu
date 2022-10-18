package org.entur.basmu.blobStore;

import org.entur.geocoder.blobStore.BlobStoreRepository;
import org.entur.geocoder.blobStore.BlobStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BalhutBlobStoreService extends BlobStoreService {

    @Value("${blobstore.gcs.haya.bucket.name:haya-dev}")
    private String targetBucketName;

    @Value("${blobstore.gcs.haya.latest.filename_without_extension:balhut_latest}")
    private String targetFilename;

    @Value("${blobstore.gcs.haya.import.folder:import}")
    private String targetFolder;

    public BalhutBlobStoreService(
            @Value("${blobstore.gcs.basmu.bucket.name:basmu-dev}") String bucketName,
            @Autowired BlobStoreRepository repository) {
        super(bucketName, repository);
    }

    public void copyBlobAsLatestToTargetBucket(String sourceName) {
        super.copyBlob(sourceName, targetBucketName, targetFolder + "/" + targetFilename + ".zip");
    }
}