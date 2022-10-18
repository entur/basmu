package org.entur.basmu.camel;

import org.apache.camel.Exchange;
import org.entur.basmu.blobStore.BasmuBlobStoreService;
import org.entur.basmu.blobStore.KakkaBlobStoreService;
import org.entur.geocoder.Utilities;
import org.entur.geocoder.ZipUtilities;
import org.entur.geocoder.camel.ErrorHandlerRouteBuilder;
import org.entur.geocoder.csv.CSVCreator;
import org.entur.geocoder.model.PeliasDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

@Component
public class BasmuRouteBuilder extends ErrorHandlerRouteBuilder {

    private static final Logger logger = LoggerFactory.getLogger(BasmuRouteBuilder.class);

    private static final String OUTPUT_FILENAME_HEADER = "balhutOutputFilename";

    @Value("${blobstore.gcs.kakka.kartverket.addresses.folder:kartverket/addresses}")
    private String kartverketAddressesFolder;

    @Value("${basmu.workdir:/tmp/basmu/geocoder}")
    private String basmuWorkDir;

    private final KakkaBlobStoreService kakkaBlobStoreService;
    private final BasmuBlobStoreService basmuBlobStoreService;
    private final PeliasDocumentMapper addressMapper;
    private final StreetMapper streetMapper;

    public BasmuRouteBuilder(
            KakkaBlobStoreService kakkaBlobStoreService,
            BasmuBlobStoreService balhutBlobStoreService,
            PeliasDocumentMapper addressMapper,
            StreetMapper streetMapper,
            @Value("${basmu.camel.redelivery.max:3}") int maxRedelivery,
            @Value("${basmu.camel.redelivery.delay:5000}") int redeliveryDelay,
            @Value("${basmu.camel.redelivery.backoff.multiplier:3}") int backOffMultiplier) {

        super(maxRedelivery, redeliveryDelay, backOffMultiplier);
        this.kakkaBlobStoreService = kakkaBlobStoreService;
        this.basmuBlobStoreService = balhutBlobStoreService;
        this.addressMapper = addressMapper;
        this.streetMapper = streetMapper;
    }

    @Override
    public void configure() {

        from("direct:makeCSV")
                .process(this::loadAddressesFile)
                .process(this::unzipAddressesFileToWorkingDirectory)
                .process(this::readAddressesCSVFile)
                .process(this::createPeliasDocumentStreamForAllIndividualAddresses)
                .process(this::addPeliasDocumentStreamForStreets)
                .process(this::createCSVFile)
                .process(this::setOutputFilenameHeader)
                .process(this::zipCSVFile)
                .process(this::uploadCSVFile)
                .process(this::copyCSVFileAsLatestToConfiguredBucket);
    }

    private void loadAddressesFile(Exchange exchange) {
        logger.debug("Loading addresses file");
        exchange.getIn().setBody(
                kakkaBlobStoreService.findLatestBlob(kartverketAddressesFolder),
                InputStream.class
        );
    }

    private void unzipAddressesFileToWorkingDirectory(Exchange exchange) {
        logger.debug("Unzipping addresses file");
        ZipUtilities.unzipFile(
                exchange.getIn().getBody(InputStream.class),
                basmuWorkDir + "/addresses"
        );
    }

    private void readAddressesCSVFile(Exchange exchange) {
        logger.debug("Read addresses CSV file");
        try (Stream<Path> paths = Files.walk(Paths.get(basmuWorkDir + "/addresses"))) {
            paths.filter(Utilities::isValidFile).findFirst().ifPresent(path -> {
                exchange.getIn().setBody(KartverketAddressReader.read(path));
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createPeliasDocumentStreamForAllIndividualAddresses(Exchange exchange) {
        logger.debug("Converting stream of kartverket addresses to stream of pelias documents.");

        @SuppressWarnings("unchecked")
        Stream<KartverketAddress> addresses = exchange.getIn().getBody(Stream.class);
        // Create documents for all individual addresses
        List<PeliasDocument> peliasDocuments = addresses.parallel()
                .map(addressMapper::toPeliasDocument).toList();

        exchange.getIn().setBody(peliasDocuments);
    }

    private void addPeliasDocumentStreamForStreets(Exchange exchange) {
        logger.debug("Adding peliasDocuments stream for unique streets");

        @SuppressWarnings("unchecked")
        List<PeliasDocument> peliasDocumentsForIndividualAddresses = exchange.getIn().getBody(List.class);

        // Create separate document per unique street
        exchange.getIn().setBody(
                Stream.concat(
                        peliasDocumentsForIndividualAddresses.stream(),
                        streetMapper.createStreetPeliasDocumentsFromAddresses(peliasDocumentsForIndividualAddresses))
        );
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
                "balhut_export_geocoder_" + System.currentTimeMillis()
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