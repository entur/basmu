package org.entur.basmu;

import org.entur.basmu.blobStore.BasmuBlobStoreService;
import org.entur.basmu.blobStore.KakkaBlobStoreService;
import org.entur.basmu.osm.mapper.ProtoBufferToPeliasDocument;
import org.entur.geocoder.ZipUtilities;
import org.entur.geocoder.blobStore.BlobStoreFiles;
import org.entur.geocoder.csv.CSVCreator;
import org.entur.geocoder.model.PeliasDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.stream.Stream;

@Service
public class BasmuService {

    private static final Logger logger = LoggerFactory.getLogger(BasmuService.class);

    @Value("${blobstore.gcs.kakka.osm.poi.folder:osm}")
    private String osmFolder;

    private final KakkaBlobStoreService kakkaBlobStoreService;
    private final BasmuBlobStoreService basmuBlobStoreService;
    private final ProtoBufferToPeliasDocument pbfMapper;

    public BasmuService(
            KakkaBlobStoreService kakkaBlobStoreService,
            BasmuBlobStoreService basmuBlobStoreService,
            ProtoBufferToPeliasDocument pbfMapper) {
        this.kakkaBlobStoreService = kakkaBlobStoreService;
        this.basmuBlobStoreService = basmuBlobStoreService;
        this.pbfMapper = pbfMapper;
    }

    @Retryable(
            value = Exception.class,
            maxAttemptsExpression = "${basmu.retry.maxAttempts:3}",
            backoff = @Backoff(
                    delayExpression = "${basmu.retry.maxDelay:5000}",
                    multiplierExpression = "${basmu.retry.backoff.multiplier:3}"))
    protected BlobStoreFiles.File findPbfPoiFile() {
        logger.info("List pbf POI file");
        return kakkaBlobStoreService.listBlobStoreFiles(osmFolder).getFiles().stream()
                .filter(file -> file.getName().endsWith(".pbf"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No PBF file found"));
    }

    @Retryable(
            value = Exception.class,
            maxAttemptsExpression = "${basmu.retry.maxAttempts:3}",
            backoff = @Backoff(
                    delayExpression = "${basmu.retry.maxDelay:5000}",
                    multiplierExpression = "${basmu.retry.backoff.multiplier:3}"))
    protected InputStream loadPbfPoiFile(BlobStoreFiles.File file) {
        logger.info("Loading pbf POI file: " + file.getName());
        return kakkaBlobStoreService.getBlob(file.getName());
    }

    protected Stream<PeliasDocument> createPeliasDocumentForPointOfInterests(InputStream inputStream) {
        logger.info("Converting to pelias documents");
        return pbfMapper.transform(inputStream);
    }

    protected InputStream createCSVFile(Stream<PeliasDocument> peliasDocuments) {
        logger.info("Creating CSV file form PeliasDocuments stream");
        return CSVCreator.create(peliasDocuments);
    }

    protected String getOutputFilename() {
        return "basmu_export_geocoder_" + System.currentTimeMillis();
    }

    protected InputStream zipCSVFile(InputStream inputStream, String filename) {
        logger.info("Zipping the created csv file");
        return ZipUtilities.zipFile(inputStream, filename + ".csv");
    }

    @Retryable(
            value = Exception.class,
            maxAttemptsExpression = "${basmu.retry.maxAttempts:3}",
            backoff = @Backoff(
                    delayExpression = "${basmu.retry.maxDelay:5000}",
                    multiplierExpression = "${basmu.retry.backoff.multiplier:3}"))
    protected void uploadCSVFile(InputStream inputStream, String filename) {
        logger.info("Uploading the CSV file");
        basmuBlobStoreService.uploadBlob(filename + ".zip", inputStream);
    }

    @Retryable(
            value = Exception.class,
            maxAttemptsExpression = "${basmu.retry.maxAttempts:3}",
            backoff = @Backoff(
                    delayExpression = "${basmu.retry.maxDelay:5000}",
                    multiplierExpression = "${basmu.retry.backoff.multiplier:3}"))
    protected void copyCSVFileAsLatestToConfiguredBucket(String filename) {
        logger.info("Coping latest file to haya");
        basmuBlobStoreService.copyBlobAsLatestToTargetBucket(filename + ".zip");
    }
}
