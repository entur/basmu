package org.entur.basmu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

import java.io.InputStream;
import java.util.stream.Stream;

@SpringBootApplication
@EnableRetry
public class BasmuApplication implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BasmuApplication.class);

    private final BasmuService bs;

    public BasmuApplication(BasmuService bs) {
        this.bs = bs;
    }

    public static void main(String[] args) {
        SpringApplication.run(BasmuApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        Stream.of(bs.findPbfPoiFile())
                .map(bs::loadPbfPoiFile)
                .map(bs::createPeliasDocumentForPointOfInterests)
                .map(bs::createCSVFile)
                .findFirst()
                .ifPresentOrElse(
                        this::zipAndUploadCSVFile,
                        () -> logger.info("No or empty pbf file found.")
                );
    }

    private void zipAndUploadCSVFile(InputStream inputStream) {
        String outputFilename = bs.getOutputFilename();
        InputStream csvZipFile = bs.zipCSVFile(inputStream, outputFilename);
        bs.uploadCSVFile(csvZipFile, outputFilename);
        bs.copyCSVFileAsLatestToConfiguredBucket(outputFilename);
        logger.info("Uploaded zipped csv files to basmu and haya");
    }
}
