package org.entur.basmu;

import org.entur.basmu.blobStore.BasmuBlobStoreService;
import org.entur.basmu.blobStore.KakkaBlobStoreService;
import org.entur.basmu.osm.domain.PointOfInterestFilter;
import org.entur.basmu.osm.mapper.ProtoBufferToPeliasDocument;
import org.entur.basmu.osm.service.OSMPOIFilterService;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

@Service
public class BasmuService {

    private static final Logger logger = LoggerFactory.getLogger(BasmuService.class);

    @Value("${blobstore.gcs.kakka.osm.poi.folder:osm}")
    private String osmFolder;

    @Value("${basmu.workdir:/tmp/basmu/geocoder}")
    private String basmuWorkDir;

    private final KakkaBlobStoreService kakkaBlobStoreService;
    private final BasmuBlobStoreService basmuBlobStoreService;
    private final OSMPOIFilterService osmpoiFilterService;

    private final ProtoBufferToPeliasDocument pbfMapper;

    public BasmuService(
            KakkaBlobStoreService kakkaBlobStoreService,
            BasmuBlobStoreService basmuBlobStoreService,
            OSMPOIFilterService osmpoiFilterService,
            ProtoBufferToPeliasDocument pbfMapper) {
        this.kakkaBlobStoreService = kakkaBlobStoreService;
        this.basmuBlobStoreService = basmuBlobStoreService;
        this.osmpoiFilterService = osmpoiFilterService;
        this.pbfMapper = pbfMapper;
    }

    @Retryable(
            value = Exception.class,
            maxAttemptsExpression = "${basmu.retry.maxAttempts:3}",
            backoff = @Backoff(
                    delayExpression = "${basmu.retry.maxDelay:5000}",
                    multiplierExpression = "${basmu.retry.backoff.multiplier:3}"))
    protected List<PointOfInterestFilter> getPoiFilters() {
        logger.info("Loading the POI filters");
        return osmpoiFilterService.getFilters();
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
        createWorkingDirectory();
        logger.info("Loading pbf POI file: " + file.getName());
        File targetFile = new File(basmuWorkDir + "/" + file.getFileNameOnly());
        try (InputStream blob = kakkaBlobStoreService.getBlob(file.getName())) {
            Files.copy(
                    blob,
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return new FileInputStream(targetFile);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected void createWorkingDirectory() {
        logger.info("Creating work directory " + basmuWorkDir);

        try {
            File workDirectory = new File(basmuWorkDir);
            if (!workDirectory.exists()) {
                Files.createDirectories(Paths.get(basmuWorkDir));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create working directory");
        }
    }

    protected Stream<PeliasDocument> createPeliasDocumentForPointOfInterests(InputStream inputStream,
                                                                             List<PointOfInterestFilter> pointOfInterestFilters) {
        logger.info("Converting to pelias documents");
        return pbfMapper.transform(inputStream, pointOfInterestFilters);
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
