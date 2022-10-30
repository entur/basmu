package org.entur.basmu.camel;

import org.apache.camel.Exchange;
import org.apache.commons.io.IOUtils;
import org.entur.basmu.blobStore.BasmuBlobStoreService;
import org.entur.basmu.blobStore.KakkaBlobStoreService;
import org.entur.basmu.osm.pbf.PbfToElasticsearchCommands;
import org.entur.geocoder.ZipUtilities;
import org.entur.geocoder.camel.ErrorHandlerRouteBuilder;
import org.entur.geocoder.csv.CSVCreator;
import org.entur.geocoder.model.PeliasDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

@Component
public class BasmuRouteBuilder extends ErrorHandlerRouteBuilder {

    private static final Logger logger = LoggerFactory.getLogger(BasmuRouteBuilder.class);

    private static final String OUTPUT_FILENAME_HEADER = "basmuOutputFilename";

    @Value("${blobstore.gcs.kakka.kartverket.addresses.folder:kartverket/addresses}")
    private String osmFolder;

    @Value("${basmu.workdir:/tmp/basmu/geocoder}")
    private String basmuWorkDir;

    private final KakkaBlobStoreService kakkaBlobStoreService;
    private final BasmuBlobStoreService basmuBlobStoreService;
    private final PbfToElasticsearchCommands pbfMapper;

    private final ApplicationContext context;

    public BasmuRouteBuilder(
            ApplicationContext context,
            KakkaBlobStoreService kakkaBlobStoreService,
            BasmuBlobStoreService basmuBlobStoreService,
            PbfToElasticsearchCommands pbfMapper,
            @Value("${basmu.camel.redelivery.max:3}") int maxRedelivery,
            @Value("${basmu.camel.redelivery.delay:5000}") int redeliveryDelay,
            @Value("${basmu.camel.redelivery.backoff.multiplier:3}") int backOffMultiplier) {

        super(maxRedelivery, redeliveryDelay, backOffMultiplier);
        this.kakkaBlobStoreService = kakkaBlobStoreService;
        this.basmuBlobStoreService = basmuBlobStoreService;
        this.pbfMapper = pbfMapper;
        this.context = context;
    }

    @Override
    public void configure() {

        from("direct:makeCSV")
                .process(this::loadPOIFile)
//                .process(this::copyFileToWorkingDirectory)
//                .process(this::createOSMMap)
                .process(this::createPeliasDocumentStreamForPointOfInterests)
                .process(this::createCSVFile)
                .process(this::setOutputFilenameHeader)
                .process(this::zipCSVFile)
                .process(this::uploadCSVFile)
                .process(this::copyCSVFileAsLatestToConfiguredBucket)
                .process(exchange -> SpringApplication.exit(context, () -> 0));
    }

    private void loadPOIFile(Exchange exchange) {
        logger.debug("Loading POI file");
        exchange.getIn().setBody(
                kakkaBlobStoreService.findLatestBlob(osmFolder), // TODO: Load latest file with given extension pbf
                InputStream.class
        );
    }

    private void copyFileToWorkingDirectory(Exchange exchange) {
        InputStream inputStream = exchange.getIn().getBody(InputStream.class);
        File tmpPoiFile = new File(basmuWorkDir + File.pathSeparator + "poi.pbf");
        tmpPoiFile.deleteOnExit();
        try (var out = new FileOutputStream(tmpPoiFile)) {
            IOUtils.copy(inputStream, out);
            exchange.getIn().setBody(tmpPoiFile);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void storeFileInWorkingDirectory(Exchange exchange) throws IOException {
        InputStream inputStream = exchange.getIn().getBody(InputStream.class);

        File targetFile = new File(basmuWorkDir + "/poi/poi.pbf");
        Files.copy(
                inputStream,
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void createPeliasDocumentStreamForPointOfInterests(Exchange exchange) {
        logger.debug("Converting to stream of pelias documents.");

        InputStream inputStream = exchange.getIn().getBody(InputStream.class);
        exchange.getIn().setBody(pbfMapper.transform(inputStream));
    }

    private void createCSVFile(Exchange exchange) {
        logger.debug("Creating CSV file form PeliasDocuments stream");

        @SuppressWarnings("unchecked")
        Stream<PeliasDocument> peliasDocuments = exchange.getIn().getBody(Stream.class);
        exchange.getIn().setBody(CSVCreator.create(peliasDocuments));
    }

    private void setOutputFilenameHeader(Exchange exchange) {
        exchange.getIn().setHeader(
                OUTPUT_FILENAME_HEADER,
                "basmu_export_geocoder_" + System.currentTimeMillis()
        );
    }

    private void zipCSVFile(Exchange exchange) {
        logger.debug("Zipping the created csv file");
        ByteArrayInputStream zipFile = ZipUtilities.zipFile(
                exchange.getIn().getBody(InputStream.class),
                exchange.getIn().getHeader(OUTPUT_FILENAME_HEADER, String.class) + ".csv"
        );
        exchange.getIn().setBody(zipFile);
    }

    private void uploadCSVFile(Exchange exchange) {
        logger.debug("Uploading the CSV file");
        basmuBlobStoreService.uploadBlob(
                exchange.getIn().getHeader(OUTPUT_FILENAME_HEADER, String.class) + ".zip",
                exchange.getIn().getBody(InputStream.class)
        );
    }

    private void copyCSVFileAsLatestToConfiguredBucket(Exchange exchange) {
        logger.debug("Coping latest file to haya");
        String currentCSVFileName = exchange.getIn().getHeader(OUTPUT_FILENAME_HEADER, String.class) + ".zip";
        basmuBlobStoreService.copyBlobAsLatestToTargetBucket(currentCSVFileName);
    }
}